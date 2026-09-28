cmake_minimum_required(VERSION 3.18)

project(fcitx5-android VERSION ${VERSION_NAME})

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

# prefab dependency
find_package(fcitx5 REQUIRED CONFIG)
get_target_property(FCITX5_CMAKE_MODULES fcitx5::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(Fcitx5Core MODULE)
find_package(Fcitx5Module MODULE)

find_package(libime REQUIRED CONFIG)
get_target_property(LIBIME_CMAKE_MODULES libime::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${LIBIME_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(LibIMECore MODULE)
find_package(LibIMEPinyin MODULE)
find_package(LibIMETable MODULE)

find_package(fcitx5-lua REQUIRED CONFIG)
find_package(fcitx5-chinese-addons REQUIRED CONFIG)

include("${FCITX_INSTALL_CMAKECONFIG_DIR}/Fcitx5Utils/Fcitx5CompilerSettings.cmake")

add_subdirectory(po)
add_subdirectory(androidfrontend)
add_subdirectory(androidkeyboard)
add_subdirectory(androidnotification)

# prebuilt libuv
set(libuv_DIR "${PREBUILT_DIR}/libuv/${ANDROID_ABI}/lib/cmake/libuv")
find_package(libuv)

# prebuilt boost
list(APPEND CMAKE_FIND_ROOT_PATH "${PREBUILT_DIR}/boost/${ANDROID_ABI}/lib/cmake")
find_package(Boost 1.86.0 REQUIRED COMPONENTS headers iostreams CONFIG)

set(CHINESE_ADDONS_PINYIN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons/im/pinyin")
add_library(pinyin-customphrase STATIC "${CHINESE_ADDONS_PINYIN_DIR}/customphrase.cpp")
target_include_directories(pinyin-customphrase INTERFACE "${CHINESE_ADDONS_PINYIN_DIR}")
target_link_libraries(pinyin-customphrase PRIVATE Fcitx5::Utils LibIME::Core)

add_library(native-lib SHARED native-lib.cpp androidaddonloader/androidaddonloader.cpp)
target_link_libraries(native-lib
        log
        libuv::uv_a
        Fcitx5::Utils
        Fcitx5::Config
        Fcitx5::Core
        Fcitx5::Module::QuickPhrase
        Fcitx5::Module::Unicode
        Fcitx5::Module::Clipboard
        Boost::headers
        Boost::iostreams
        LibIME::Pinyin
        LibIME::Table
        pinyin-customphrase
        )

# copy module libraries from dependency lib
add_custom_target(copy-fcitx5-modules
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_PROPERTY:fcitx5::clipboard,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5::imselector,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5::quickphrase,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5::spell,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5::unicode,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5-lua::luaaddonloader,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5-chinese-addons::pinyin,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5-chinese-addons::table,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5-chinese-addons::scel2org5,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5-chinese-addons::chttrans,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5-chinese-addons::fullwidth,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5-chinese-addons::pinyinhelper,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5-chinese-addons::punctuation,IMPORTED_LOCATION>
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
        COMMENT "Copying fcitx5 module libraries to :app"
        )

# install prebuilt assets
install(FILES "${PREBUILT_DIR}/spell-dict/en_dict.fscd" DESTINATION "${FCITX_INSTALL_PKGDATADIR}/spell" COMPONENT prebuilt-assets)
install(FILES "${PREBUILT_DIR}/chinese-addons-data/pinyin/chaizi.dict" DESTINATION "${FCITX_INSTALL_PKGDATADIR}/pinyin" COMPONENT prebuilt-assets)
install(DIRECTORY "${PREBUILT_DIR}/chinese-addons-data/pinyinhelper" DESTINATION "${FCITX_INSTALL_PKGDATADIR}" COMPONENT prebuilt-assets)
install(DIRECTORY "${PREBUILT_DIR}/libime/table" DESTINATION "${FCITX_INSTALL_PKGDATADIR}" COMPONENT prebuilt-assets)
install(DIRECTORY "${PREBUILT_DIR}/libime/data/" DESTINATION "${FCITX_INSTALL_DATADIR}/libime" COMPONENT prebuilt-assets)
install(DIRECTORY "${PREBUILT_DIR}/opencc/data/" DESTINATION "${FCITX_INSTALL_DATADIR}/opencc" COMPONENT prebuilt-assets)

# ============================================================================
# 【fainput / L3】llama.cpp —— 后台整理层的推理后端
#
# ## 源码不在仓库里
#
# CI 在构建前按**固定 tag** 拉下来（见 .github/workflows/build.yml）。
# 为什么不 vendor：源码 ~40MB，而这条线路 push 只有 ~38KB/s。
# 为什么锁 tag：llama.cpp 自己说 ABI **不稳定**（docs/build.md §Notes），
#             而且它的 API 变动极快（`llama_load_model_from_file` 就被改名过）。
#
# ## 本地没有也能编
#
# 下面带 `EXISTS` 守卫 —— 缺源码时 `native-llm` 编成一堆桩函数，
# App 其余功能完全不受影响。**不会出现"少一个目录整个项目编不了"。**
#
# ## 开关来自官方 docs/build.md 的 Android 段
#
#   GGML_NATIVE=OFF    别按宿主机 CPU 生成指令（交叉编译必须）
#   GGML_OPENMP=OFF    NDK 用不了 OpenMP
#   GGML_LLAMAFILE=OFF llamafile 不支持 Android
#   LLAMA_OPENSSL=OFF  我们零网络，不需要 TLS
# ============================================================================
set(FAINPUT_LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if(EXISTS "${FAINPUT_LLAMA_DIR}/CMakeLists.txt")
    set(FAINPUT_WITH_LLAMA ON)
    # 只要库本身：tests / tools / examples / server / common / UI 全关。
    # （作为 add_subdirectory 进来时 LLAMA_STANDALONE 本来就是 OFF，
    #   这里再显式钉死一遍，免得上游改默认值。）
    set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_UI        OFF CACHE BOOL "" FORCE)
    set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
    set(LLAMA_LLGUIDANCE      OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
    # 静态链进 native-llm，不在 APK 里多塞几个 .so
    set(FAINPUT_SHARED_BACKUP ${BUILD_SHARED_LIBS})
    set(BUILD_SHARED_LIBS OFF)
    # 静态库要被链进共享库，必须带 PIC。
    # Android 工具链本来就是 ON，这里显式钉一遍 —— 否则一旦哪天变 OFF，
    # 报出来的会是 "relocation ... can not be used when making a shared object"
    # 这种要查半天的链接错误。
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    # EXCLUDE_FROM_ALL：llama.cpp 自己的 install 规则不要进来
    # （否则它的头文件和静态库会被打进 APK 的 assets）
    add_subdirectory(${FAINPUT_LLAMA_DIR} "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)
    set(BUILD_SHARED_LIBS ${FAINPUT_SHARED_BACKUP})
    message(STATUS "fainput: llama.cpp 已启用 —— 后台整理层可用")
else()
    set(FAINPUT_WITH_LLAMA OFF)
    message(STATUS "fainput: 没找到 llama.cpp 源码 —— native-llm 编为桩（功能降级）")
endif()

add_library(native-llm SHARED llm-jni.cpp)
target_compile_definitions(native-llm PRIVATE "FAINPUT_WITH_LLAMA=$<BOOL:${FAINPUT_WITH_LLAMA}>")
target_link_libraries(native-llm log)
if(FAINPUT_WITH_LLAMA)
    target_link_libraries(native-llm llama)
endif()
