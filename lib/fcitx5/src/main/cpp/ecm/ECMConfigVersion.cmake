# ---------------------------------------------------------------------------
# ECMConfigVersion.cmake —— 供 fcitx5-android 的 vendored ECM 使用
#
# 语义等价于 write_basic_package_version_file(... COMPATIBILITY AnyNewerVersion)：
#   请求版本 <= 6.14.0  =>  兼容
#
# 调用方需求：
#   fcitx5/CMakeLists.txt:4      find_package(ECM REQUIRED 1.0.0)
#   libime/CMakeLists.txt:6      find_package(ECM 1.0 REQUIRED)
#   两者最终都会走 lib/fcitx5/src/main/cpp/cmake/FindECM.cmake 里的
#   find_package(ECM REQUIRED CONFIG)，因此这里必须能通过 1.0.0 / 1.0 的检查。
# ---------------------------------------------------------------------------

set(PACKAGE_VERSION "6.14.0")

if("${PACKAGE_FIND_VERSION}" STREQUAL "")
    # 未指定版本：无条件兼容
    set(PACKAGE_VERSION_COMPATIBLE TRUE)
elseif(PACKAGE_VERSION VERSION_LESS PACKAGE_FIND_VERSION)
    set(PACKAGE_VERSION_COMPATIBLE FALSE)
else()
    set(PACKAGE_VERSION_COMPATIBLE TRUE)
    if(PACKAGE_FIND_VERSION STREQUAL PACKAGE_VERSION)
        set(PACKAGE_VERSION_EXACT TRUE)
    endif()
endif()