#include "waylandkeys.h"

#include <spawn.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <thread>
#include <utility>

extern char **environ;

namespace textbridge {

std::vector<std::string> waylandKeyArguments(const fcitx::Key &key) {
    std::vector<std::string> arguments;
    std::vector<std::string> modifiers;
    for (const auto &[state, name] : {
             std::pair{fcitx::KeyState::Ctrl, "ctrl"},
             std::pair{fcitx::KeyState::Shift, "shift"},
             std::pair{fcitx::KeyState::Alt, "alt"}}) {
        if (key.states().test(state)) {
            modifiers.emplace_back(name);
            arguments.insert(arguments.end(), {"-M", name});
        }
    }
    arguments.insert(arguments.end(), {"-k", fcitx::Key::keySymToString(key.sym())});
    for (auto it = modifiers.rbegin(); it != modifiers.rend(); ++it) {
        arguments.insert(arguments.end(), {"-m", *it});
    }
    return arguments;
}

bool forwardWaylandKey(const fcitx::Key &key, const std::string &executable) {
    auto arguments = waylandKeyArguments(key);
    arguments.insert(arguments.begin(), executable);
    std::vector<char *> argv;
    for (auto &argument : arguments) {
        argv.push_back(argument.data());
    }
    argv.push_back(nullptr);

    pid_t child;
    // No shell: all arguments come from the validated key and modifier list.
    if (posix_spawn(&child, executable.c_str(), nullptr, nullptr, argv.data(), environ) != 0) {
        return false;
    }

    // Keep failure bounded below the server request timeout. Disconnecting the
    // virtual keyboard also releases its modifiers if the helper gets stuck.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(750);
    int status;
    while (std::chrono::steady_clock::now() < deadline) {
        auto result = waitpid(child, &status, WNOHANG);
        if (result == child) {
            return WIFEXITED(status) && WEXITSTATUS(status) == 0;
        }
        if (result < 0 && errno != EINTR) {
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    kill(child, SIGKILL);
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    return false;
}

} // namespace textbridge
