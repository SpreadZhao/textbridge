#include "../src/keyactions.h"
#include "../src/waylandkeys.h"

#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>

int main(int argc, char **argv) {
    // The test executable doubles as a child process to exercise real spawn,
    // exit-status handling, timeout and reaping without a compositor in CTest.
    if (argc > 1) {
        for (int i = 1; i < argc; ++i) {
            if (std::string(argv[i]) == "Escape") {
                return 42;
            }
            if (std::string(argv[i]) == "Tab") {
                std::this_thread::sleep_for(std::chrono::seconds(5));
            }
        }
        return 0;
    }
    auto require = [](bool condition, const char *message) {
        if (!condition) {
            throw std::runtime_error(message);
        }
    };
    auto combination = textbridge::makeForwardKey("Return", {"Control", "Shift", "Alt"});
    require(textbridge::waylandKeyArguments(*combination) == std::vector<std::string>{
                "-M", "ctrl", "-M", "shift", "-M", "alt", "-k", "Return",
                "-m", "alt", "-m", "shift", "-m", "ctrl"},
            "Modifiers must be pressed before the main key and fully released afterwards");
    require(textbridge::waylandKeyArguments(*textbridge::makeForwardKey("A", {})) ==
                std::vector<std::string>{"-k", "a"}, "Wire letter must map to its keysym");
    require(textbridge::forwardWaylandKey(*combination, "/proc/self/exe"), "Successful child failed");
    require(!textbridge::forwardWaylandKey(*textbridge::makeForwardKey("Escape", {}), "/proc/self/exe"),
            "Nonzero child exit must report failure");
    require(!textbridge::forwardWaylandKey(*combination, "/nonexistent/textbridge-wtype"),
            "Missing helper must report failure");
    const auto start = std::chrono::steady_clock::now();
    require(!textbridge::forwardWaylandKey(*textbridge::makeForwardKey("Tab", {}), "/proc/self/exe"),
            "Stuck child must time out");
    require(std::chrono::steady_clock::now() - start < std::chrono::seconds(2), "Timeout exceeded request budget");
    int status;
    require(waitpid(-1, &status, WNOHANG) == -1 && errno == ECHILD, "Child process leaked");
    std::cout << "Verified modifier lifecycle, helper failures and bounded process cleanup\n";
}
