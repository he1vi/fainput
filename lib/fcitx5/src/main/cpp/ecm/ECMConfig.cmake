# ---------------------------------------------------------------------------
# 手写的 ECMConfig.cmake —— 供 fcitx5-android 在 AndroidCS 上离线构建使用
#
# 上游 ECM 是用 CMake 的 configure_package_config_file() 从 ECMConfig.cmake.in
# 生成这个文件的（模板见 extra-cmake-modules/ECMConfig.cmake.in）。
# 这里直接复刻它的最终形态，把三个模块目录指向随仓库 vendored 的副本，
# 从而完全不依赖系统是否装了 extra-cmake-modules。
#
# 覆盖范围说明：
#   在 Android 配置下（ENABLE_X11=OFF / ENABLE_WAYLAND=OFF / ENABLE_DBUS=OFF
#   / ENABLE_KEYBOARD=OFF），fcitx5、libime、fcitx5-lua、fcitx5-chinese-addons
#   四个工程真正调用到的 ECM 宏只有 ecm_setup_version()。
#   ecm_generate_headers / ecm_generate_pkgconfig_file 只被 include 未被调用；
#   ecm_find_package_* 只出现在 FindPango.cmake / FindXKBCommon.cmake 里，
#   而这两个 Find 模块只在 X11/Wayland 开启时才会被用到。
#   其余 ECM 模块一并 vendored，纯粹是为了保证 include() 不会找不到文件。
#
# vendored 来源：KDE extra-cmake-modules v6.14.0（GitHub 官方镜像 tag）
# ---------------------------------------------------------------------------

set(ECM_VERSION "6.14.0")

get_filename_component(_ecm_prefix "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)

set(ECM_MODULE_DIR      "${_ecm_prefix}/modules")
set(ECM_FIND_MODULE_DIR "${_ecm_prefix}/find-modules")
set(ECM_KDE_MODULE_DIR  "${_ecm_prefix}/kde-modules")
set(ECM_PREFIX          "${_ecm_prefix}")

set(ECM_MODULE_PATH
    "${ECM_MODULE_DIR}"
    "${ECM_FIND_MODULE_DIR}"
    "${ECM_KDE_MODULE_DIR}"
)

set(ECM_GLOBAL_FIND_VERSION "${ECM_FIND_VERSION}")

# 上游模板的最后一行。v6.14.0 里这个文件已被抽空成纯注释，include 它无害。
include("${ECM_MODULE_DIR}/ECMUseFindModules.cmake")

unset(_ecm_prefix)