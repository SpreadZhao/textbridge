cmake_minimum_required(VERSION 3.16)
project(fcitx5-textbridge VERSION 0.1.0 LANGUAGES CXX)

include(GNUInstallDirs)

find_package(Fcitx5Core REQUIRED)
find_program(WTYPE_EXECUTABLE NAMES wtype)
if(NOT WTYPE_EXECUTABLE)
    message(FATAL_ERROR "wtype is required for Wayland key forwarding")
endif()

add_library(textbridge-keys STATIC src/keyactions.cpp src/waylandkeys.cpp)
target_compile_features(textbridge-keys PUBLIC cxx_std_20)
target_compile_options(textbridge-keys PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(textbridge-keys PUBLIC Fcitx5::Utils)
set_target_properties(textbridge-keys PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(textbridge MODULE src/textbridge.cpp)
target_compile_features(textbridge PRIVATE cxx_std_20)
target_compile_options(textbridge PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(textbridge PRIVATE _GNU_SOURCE)
target_compile_definitions(textbridge PRIVATE TEXTBRIDGE_WTYPE_EXECUTABLE="${WTYPE_EXECUTABLE}")
target_link_libraries(textbridge PRIVATE Fcitx5::Core textbridge-keys)
set_target_properties(textbridge PROPERTIES PREFIX "")

include(CTest)
if(BUILD_TESTING)
    add_executable(test-keyactions tests/test_keyactions.cpp)
    target_link_libraries(test-keyactions PRIVATE textbridge-keys)
    add_test(NAME keyactions COMMAND test-keyactions)
    add_executable(test-waylandkeys tests/test_waylandkeys.cpp)
    target_link_libraries(test-waylandkeys PRIVATE textbridge-keys)
    add_test(NAME waylandkeys COMMAND test-waylandkeys)
endif()

configure_file(data/textbridge.conf.in textbridge.conf @ONLY)

install(TARGETS textbridge
    LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}/fcitx5")
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/textbridge.conf"
    DESTINATION "${CMAKE_INSTALL_DATADIR}/fcitx5/addon")
