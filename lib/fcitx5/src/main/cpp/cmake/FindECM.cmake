# ---------------------------------------------------------------------------
# 【fainput 改动】优先使用随仓库 vendored 的 ECM（lib/fcitx5/src/main/cpp/ecm/）
#
# 上游这个文件把 Linux 分支硬编码成 /usr/share/ECM/cmake，这在 Android 设备
# 上永远不存在，而 AndroidCS 的终端仓库也没有 extra-cmake-modules 包。
# 因此把 vendored 副本放在查找链的最前面。
#
# 查找顺序：仓库内 vendored 副本  ->  环境变量 ECM_DIR  ->  上游平台默认路径
# ---------------------------------------------------------------------------
get_filename_component(_fcitx5_android_vendored_ecm
    "${CMAKE_CURRENT_LIST_DIR}/../ecm" ABSOLUTE)

if(EXISTS "${_fcitx5_android_vendored_ecm}/ECMConfig.cmake")
    set(ECM_DIR "${_fcitx5_android_vendored_ecm}")
elseif(DEFINED ENV{ECM_DIR})
    set(ECM_DIR $ENV{ECM_DIR})
elseif(CMAKE_HOST_WIN32)
    set(ECM_DIR "C:/msys64/ucrt64/share/ECM/cmake")
elseif(CMAKE_HOST_APPLE)
    if(CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "arm64")
        set(ECM_DIR /opt/homebrew/share/ECM/cmake)
    else()
        set(ECM_DIR /usr/local/share/ECM/cmake)
    endif()
else()
    set(ECM_DIR /usr/share/ECM/cmake)
endif()

find_package(ECM REQUIRED CONFIG)
