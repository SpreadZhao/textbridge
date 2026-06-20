cmake_minimum_required(VERSION 3.16)
project(fcitx5-textbridge VERSION 0.1.0 LANGUAGES CXX)

include(GNUInstallDirs)

find_package(Fcitx5Core REQUIRED)

add_library(textbridge MODULE src/textbridge.cpp)
target_compile_features(textbridge PRIVATE cxx_std_20)
target_compile_options(textbridge PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(textbridge PRIVATE _GNU_SOURCE)
target_link_libraries(textbridge PRIVATE Fcitx5::Core Fcitx5::Utils)
set_target_properties(textbridge PROPERTIES PREFIX "")

configure_file(data/textbridge.conf.in textbridge.conf @ONLY)

install(TARGETS textbridge
    LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}/fcitx5")
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/textbridge.conf"
    DESTINATION "${CMAKE_INSTALL_DATADIR}/fcitx5/addon")
