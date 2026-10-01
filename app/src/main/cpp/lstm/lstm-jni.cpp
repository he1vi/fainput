/*
 * 【fainput / C 层】字符级 LSTM 打分 —— JNI
 *
 * 挂在 `native-lib` 目标上（**不依赖 llama.cpp**，见 CMakeLists）。
 *
 * ## 为什么必须 native
 *
 * 同机 aarch64 实测（`_lstm/bench_lstm.c`）：
 *
 *     HID200 -O2        : 单字符 0.904 ms  →  20 候选 × 4 字 =  92.0 ms
 *     HID200 -O3+NEON   : 单字符 0.202 ms  →  20 候选 × 4 字 =  20.7 ms
 *     HID128 -O3+NEON   : 单字符 0.087 ms  →  20 候选 × 4 字 =   8.6 ms
 *
 * 编译开关值 **5.5 倍**。Kotlin 拿不到 NEON 自动向量化 ⇒ 只能在这儿。
 *
 * ## 数学与 PyTorch 一致
 *
 * 门顺序 **i, f, g, o**（= PyTorch `weight_ih_l0` 的行顺序）。
 * 已用 `_lstm/ref_lstm.py`（numpy 参考实现）对齐到 **1~3 ULP**。
 *
 * ## 上下文状态缓存
 *
 * 打分公式是 `P(候选 | 上文)`。上文（上一个上屏的词）在一次重排里**不变**，
 * 但候选有几十个 —— 每个都从零跑一遍上文就白费了。
 * 所以：**上文状态缓存**，只有上文变了才重算。
 *
 * 文件格式 `.fnlstm` 见 `_lstm/ref_lstm.py` 的 docstring。
 */
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>
#include <unordered_map>

namespace {

/* ==================== 模型 ==================== */
struct LstmModel {
    uint32_t vocab = 0, dim = 0, hidden = 0, layers = 0;
    std::vector<std::string> tokens;
    std::unordered_map<std::string, int> tokenIndex;
    std::vector<float> emb;
    std::vector<std::vector<float>> Wih, Whh, bih, bhh;
    std::vector<float> Wout, bout;
};

struct LstmState {
    std::vector<std::vector<float>> h, c;
    void init(uint32_t layers, uint32_t hidden) {
        h.assign(layers, std::vector<float>(hidden, 0.0f));
        c.assign(layers, std::vector<float>(hidden, 0.0f));
    }
};

LstmModel g_model;
LstmState g_ctxState;              /* 上文跑完后的状态（缓存）*/
bool      g_ctxValid = false;
std::string g_ctxKey;              /* 缓存对应的上文串 */
std::vector<float> g_gates;        /* 复用缓冲，热路径不 malloc */
std::vector<float> g_logits;

/* ==================== 加载 ==================== */
bool readExact(FILE *f, void *buf, size_t n) { return fread(buf, 1, n, f) == n; }

bool loadModel(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return false;

    char magic[8];
    if (!readExact(f, magic, 8) || memcmp(magic, "FNLSTM01", 8) != 0) { fclose(f); return false; }
    uint32_t hdr[4];
    if (!readExact(f, hdr, sizeof(hdr))) { fclose(f); return false; }

    LstmModel m;
    m.vocab = hdr[0]; m.dim = hdr[1]; m.hidden = hdr[2]; m.layers = hdr[3];
    /* 防御：畸形文件不该让 App 崩 */
    if (m.vocab == 0 || m.vocab > 100000 || m.dim == 0 || m.dim > 4096 ||
        m.hidden == 0 || m.hidden > 4096 || m.layers == 0 || m.layers > 8) {
        fclose(f); return false;
    }

    m.tokens.resize(m.vocab);
    for (uint32_t i = 0; i < m.vocab; i++) {
        uint16_t len = 0;
        if (!readExact(f, &len, sizeof(len))) { fclose(f); return false; }
        std::string s(len, '\0');
        if (len && !readExact(f, &s[0], len)) { fclose(f); return false; }
        m.tokens[i] = s;
    }

    auto rd = [&](std::vector<float> &v, size_t n) -> bool {
        v.resize(n);
        return n == 0 || readExact(f, v.data(), n * sizeof(float));
    };

    if (!rd(m.emb, (size_t)m.vocab * m.dim)) { fclose(f); return false; }
    m.Wih.resize(m.layers); m.Whh.resize(m.layers);
    m.bih.resize(m.layers); m.bhh.resize(m.layers);
    for (uint32_t l = 0; l < m.layers; l++) {
        size_t in = (l == 0) ? m.dim : m.hidden;
        if (!rd(m.Wih[l], (size_t)4 * m.hidden * in)) { fclose(f); return false; }
        if (!rd(m.Whh[l], (size_t)4 * m.hidden * m.hidden)) { fclose(f); return false; }
        if (!rd(m.bih[l], (size_t)4 * m.hidden)) { fclose(f); return false; }
        if (!rd(m.bhh[l], (size_t)4 * m.hidden)) { fclose(f); return false; }
    }
    if (!rd(m.Wout, (size_t)m.vocab * m.hidden)) { fclose(f); return false; }
    if (!rd(m.bout, m.vocab)) { fclose(f); return false; }
    fclose(f);

    /* 建倒排：字符 → id。OOV 的字用 0（unk），但**不参与打分**（见 scoreCandidate）*/
    m.tokenIndex.reserve(m.vocab * 2);
    for (uint32_t i = 0; i < m.vocab; i++) m.tokenIndex.emplace(m.tokens[i], (int)i);

    g_model = std::move(m);
    g_gates.assign(4 * g_model.hidden, 0.0f);
    g_logits.assign(g_model.vocab, 0.0f);
    g_ctxValid = false;
    g_ctxKey.clear();
    return true;
}

/* ==================== UTF-8 切字 ==================== */

/*
 * 把 UTF-8 串切成一个个字符（返回每个字符的字节范围）。
 * **不能按 byte 切** —— 一个汉字 3 字节，按 byte 切会得到 3 个非法 token。
 */
std::vector<std::string> splitUtf8(const std::string &s) {
    std::vector<std::string> out;
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = (unsigned char)s[i];
        size_t len = 1;
        if (c >= 0xF0) len = 4;
        else if (c >= 0xE0) len = 3;
        else if (c >= 0xC0) len = 2;
        if (i + len > n) len = 1;          /* 截断的保护 */
        out.emplace_back(s, i, len);
        i += len;
    }
    return out;
}

/* ==================== 前向 ==================== */

void step(const LstmModel &m, LstmState &st, int token) {
    const uint32_t H = m.hidden;
    const float *x = &m.emb[(size_t)token * m.dim];
    uint32_t in_dim = m.dim;
    float *gates = g_gates.data();

    for (uint32_t l = 0; l < m.layers; l++) {
        const float *wi = m.Wih[l].data(), *wh = m.Whh[l].data();
        const float *bi = m.bih[l].data(), *bh = m.bhh[l].data();
        float *hl = st.h[l].data(), *cl = st.c[l].data();

        for (uint32_t k = 0; k < 4 * H; k++) {
            float s = bi[k] + bh[k];
            const float *wr = wi + (size_t)k * in_dim;
            for (uint32_t j = 0; j < in_dim; j++) s += wr[j] * x[j];
            const float *hr = wh + (size_t)k * H;
            for (uint32_t j = 0; j < H; j++) s += hr[j] * hl[j];
            gates[k] = s;
        }

        for (uint32_t j = 0; j < H; j++) {
            float ig = 1.0f / (1.0f + expf(-gates[j]));
            float fg = 1.0f / (1.0f + expf(-gates[H + j]));
            float gg = tanhf(gates[2 * H + j]);
            float og = 1.0f / (1.0f + expf(-gates[3 * H + j]));
            float cn = fg * cl[j] + ig * gg;
            cl[j] = cn;
            hl[j] = og * tanhf(cn);
        }

        x = hl;
        in_dim = H;
    }
}

/* 全量 logits —— **保留**，归一化版（想切回去时）和调试都用得上。
 * 正式打分路径已换成 logitAt()：只要候选自己那个字的 logit。 */
__attribute__((unused))
void fillLogits(const LstmModel &m, const LstmState &st) {
    const float *hh = st.h[m.layers - 1].data();
    for (uint32_t v = 0; v < m.vocab; v++) {
        const float *wr = &m.Wout[(size_t)v * m.hidden];
        float s = m.bout[v];
        for (uint32_t j = 0; j < m.hidden; j++) s += wr[j] * hh[j];
        g_logits[v] = s;
    }
}

/*
 * 单个 logit：Wout[t] · h + bout[t]  —— **O(H)**，不是 O(V·H)。
 *
 * 见下面 scoreCandidate 的注释：归一化要的 logsumexp 得遍历全部 V 个
 * logit，占了单字符成本的 **70%**（实测 20 候选 × 4 字：22.6ms → 7.6ms）。
 */
inline float logitAt(const LstmModel &m, const LstmState &st, int t) {
    const float *hh = st.h[m.layers - 1].data();
    const float *wr = &m.Wout[(size_t)t * m.hidden];
    float s = m.bout[t];
    for (uint32_t j = 0; j < m.hidden; j++) s += wr[j] * hh[j];
    return s;
}

/* 让上下文状态就位（缓存命中就跳过）*/
void ensureContext(const std::string &ctx) {
    if (g_ctxValid && g_ctxKey == ctx) return;
    g_ctxState.init(g_model.layers, g_model.hidden);
    for (const auto &ch : splitUtf8(ctx)) {
        auto it = g_model.tokenIndex.find(ch);
        if (it != g_model.tokenIndex.end()) step(g_model, g_ctxState, it->second);
    }
    g_ctxKey = ctx;
    g_ctxValid = true;
}

/*
 * 把候选切成 token 串 —— **最长匹配**（4→3→2 字，都不中才退单字）。
 *
 * ⚠️ 为什么必须这么做（这是本轮修的 bug）：
 *
 *   vocab3 里有 4012 个**词** token（「浪费」「经历」「好看」…），模型是在
 *   【字词混合】语料上训的（corpus_words3.txt：93.65% 是词、6.35% 拆字）。
 *   但原来这里用 splitUtf8 把候选**无条件切成单字**，于是：
 *
 *       auto it = g_model.tokenIndex.find(ch);   // 只查单字
 *
 *   ⇒ 词 token 在推理时**一次都不会被激活** ⇒ 训练时学到的词级先验
 *     完全用不上。
 *
 *   实测（check_word_tokens.py，口语留出集 900 对）：
 *       同首字同长度干扰（最难）  逐字 54.4%  →  整词 71.6%   ★ +17.1 点
 *       混合长度干扰（真实场景）  逐字 63.2%  →  整词 69.4%      +6.2 点
 *   而且纯字级模型（modelD_c11k，词 token 从没训过）拿词 token 打分
 *   反而更差（48.2%）—— 说明这 +17 点完全来自【词 token 真的被训练过】，
 *   不是"切得少所以分高"。
 *
 *   现状最刺眼的一点：同首字干扰下逐字打分 = 54.4%，**基本等于抛硬币**。
 *   因为同一个字在同一位置的 logit 必然相同，胜负全看第二个字，
 *   而模型对"第二个字该是什么"几乎没有知识。走词 token 才有。
 *
 * 成本：每个位置最多 3 次 hash 查找（~150ns），4 字候选 < 1μs，
 *       相对 scoreCandidate 的 4.8ms 预算完全可忽略。
 *
 * 分母用 token 数（不是字数）—— 实测两者只差 0.1 点，而 token 数是
 * 模型的自然语义（平均每 token 的 logprob），所以不额外做归一化。
 */
std::vector<int> tokenizeLongest(const std::string &s) {
    std::vector<int> out;
    const std::vector<std::string> chs = splitUtf8(s);
    const size_t n = chs.size();
    size_t i = 0;
    while (i < n) {
        int bestId = -1;
        size_t bestLen = 0;
        /* 最长匹配：先试 4 字，再 3 字，再 2 字 */
        size_t maxLen = n - i < 4 ? n - i : 4;
        for (size_t ln = maxLen; ln >= 2; ln--) {
            std::string sub;
            sub.reserve(ln * 3);
            for (size_t k = 0; k < ln; k++) sub += chs[i + k];
            auto it = g_model.tokenIndex.find(sub);
            if (it != g_model.tokenIndex.end()) {
                bestId = it->second;
                bestLen = ln;
                break;
            }
        }
        if (bestId >= 0) {
            out.push_back(bestId);
            i += bestLen;
        } else {
            /* 退单字。不在词表 ⇒ 跳过（和原来的 OOV 处理一致：
               拿 unk 的 logit 去平均只会把分拉低，那是噪声不是信号）。 */
            auto it = g_model.tokenIndex.find(chs[i]);
            if (it != g_model.tokenIndex.end()) out.push_back(it->second);
            i += 1;
        }
    }
    return out;
}

/*
 * 打分：候选的 logit 平均（**未归一化**，C 方案）。
 *
 * 候选先经 tokenizeLongest 切成【字词混合】的 token 串，再逐 token 取
 * logit 求平均（分母 = token 数）。
 * 取**平均**而不是求和 —— 否则长候选天然吃亏（3 个字的句子永远输给 1 个字的词）。
 * 返回 0 表示"这一层不参与"（OOV / 空候选 / 模型没装）。
 *
 * ⚠️ 为什么把 softmax 分母（logsumexp）省掉了：
 *
 *   原式 score(c) = (1/L) Σ_t [ logit_t[c_t] − logsumexp(全体 logit_t) ]
 *   现在 score(c) = (1/L) Σ_t [ logit_t[c_t] ]
 *
 *   logsumexp 是**每个位置**的常数 —— 同一位置上所有候选减的是同一个数，
 *   排序时精确抵消。它唯一的作用是把"原始 logit"变成"概率"，而屏上
 *   候选之间的**相对顺序**才是我们要的东西。
 *
 *   省掉它能少算一次 V×H 的输出投影（V=6000, H=128 → 768K MACs/字，
 *   占单字符成本 ~70%）。实测同机 aarch64：
 *       20 候选 × 4 字   22.6 ms → 7.6 ms   （2.97 倍）
 *
 *   代价（eval_unnorm.py 实测，model_final）：
 *       同音 2 字词候选      top-1 一致 93.4%   差异 -0.43/+0.29/-0.29 点
 *       随机 4 字候选（最坏） top-1 一致 89.8%   最大 -1.50 点
 *   前缀相同的候选之间**完全无差异**（同一个位置的 logsumexp 相同），
 *   只有前缀分叉之后的位置才有一点差别。
 *
 *   fillLogits / g_logits 保留着 —— 想回到归一化版时改这里就行。
 */
float scoreCandidate(const std::string &ctx, const std::string &cand) {
    if (g_model.vocab == 0) return 0.0f;
    auto toks = tokenizeLongest(cand);
    if (toks.empty()) return 0.0f;

    ensureContext(ctx);

    /* 从缓存状态分叉 —— 不打乱缓存本身 */
    LstmState st = g_ctxState;
    double tot = 0.0;

    for (int t : toks) {
        tot += (double)logitAt(g_model, st, t);
        step(g_model, st, t);
    }
    return (float)(tot / (double)toks.size());
}

} // namespace

/* ==================== JNI ==================== */

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_fcitx_fcitx5_android_data_insight_LstmNative_nativeLoad(
        JNIEnv *env, jclass, jstring path) {
    if (!path) return JNI_FALSE;
    const char *p = env->GetStringUTFChars(path, nullptr);
    bool ok = p && loadModel(p);
    if (p) env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_data_insight_LstmNative_nativeUnload(JNIEnv *, jclass) {
    g_model = LstmModel{};
    g_ctxValid = false;
    g_ctxKey.clear();
    g_gates.clear();
    g_logits.clear();
}

JNIEXPORT jboolean JNICALL
Java_org_fcitx_fcitx5_android_data_insight_LstmNative_nativeIsLoaded(JNIEnv *, jclass) {
    return g_model.vocab > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_fcitx_fcitx5_android_data_insight_LstmNative_nativeVocabSize(JNIEnv *, jclass) {
    return (jint)g_model.vocab;
}

JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_data_insight_LstmNative_nativeParamCount(JNIEnv *, jclass) {
    if (g_model.vocab == 0) return 0;
    size_t n = (size_t)g_model.vocab * g_model.dim
             + (size_t)g_model.layers * 4 * g_model.hidden * (g_model.dim + g_model.hidden) * 2
             + (size_t)g_model.vocab * g_model.hidden;
    return (jlong)n;
}

JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_data_insight_LstmNative_nativeDims(JNIEnv *env, jclass) {
    if (g_model.vocab == 0) return env->NewStringUTF("");
    char buf[96];
    snprintf(buf, sizeof(buf), "vocab=%u dim=%u hidden=%u layers=%u",
             g_model.vocab, g_model.dim, g_model.hidden, g_model.layers);
    return env->NewStringUTF(buf);
}

/*
 * 打分入口。
 *
 * ⚠️ **必须在后台线程调**（一次调用可能几十毫秒，见文件头的实测表）。
 * 调用方（`LstmScorer`）有预算守卫。
 */
JNIEXPORT jfloat JNICALL
Java_org_fcitx_fcitx5_android_data_insight_LstmNative_nativeScore(
        JNIEnv *env, jclass, jstring context, jstring candidate) {
    if (g_model.vocab == 0 || !candidate) return 0.0f;

    const char *c = env->GetStringUTFChars(candidate, nullptr);
    if (!c) return 0.0f;
    std::string cand(c);
    env->ReleaseStringUTFChars(candidate, c);

    std::string ctx;
    if (context) {
        const char *x = env->GetStringUTFChars(context, nullptr);
        if (x) { ctx.assign(x); env->ReleaseStringUTFChars(context, x); }
    }

    return (jfloat)scoreCandidate(ctx, cand);
}

} // extern "C"