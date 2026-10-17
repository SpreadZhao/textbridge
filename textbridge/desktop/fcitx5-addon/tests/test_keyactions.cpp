#include "../src/keyactions.h"

#include <iostream>
#include <stdexcept>
#include <utility>

int main() {
    std::vector<std::pair<std::string, fcitx::KeySym>> keys = {
        {"Return", FcitxKey_Return}, {"Escape", FcitxKey_Escape},
        {"Tab", FcitxKey_Tab}, {"BackSpace", FcitxKey_BackSpace},
        {"Delete", FcitxKey_Delete}, {"Space", FcitxKey_space},
        {"Left", FcitxKey_Left}, {"Right", FcitxKey_Right},
        {"Up", FcitxKey_Up}, {"Down", FcitxKey_Down},
        {"Home", FcitxKey_Home}, {"End", FcitxKey_End},
        {"Page_Up", FcitxKey_Page_Up}, {"Page_Down", FcitxKey_Page_Down},
    };
    for (char c = 'A'; c <= 'Z'; ++c) {
        keys.emplace_back(std::string(1, c), static_cast<fcitx::KeySym>(FcitxKey_a + c - 'A'));
    }
    for (int i = 0; i < 10; ++i) {
        keys.emplace_back(std::to_string(i), static_cast<fcitx::KeySym>(FcitxKey_0 + i));
    }
    for (int i = 1; i <= 12; ++i) {
        keys.emplace_back("F" + std::to_string(i), static_cast<fcitx::KeySym>(FcitxKey_F1 + i - 1));
    }

    const std::vector<std::pair<std::string, fcitx::KeyState>> modifierStates = {
        {"Control", fcitx::KeyState::Ctrl}, {"Shift", fcitx::KeyState::Shift}, {"Alt", fcitx::KeyState::Alt},
    };
    for (const auto &[name, sym] : keys) {
        for (int mask = 0; mask < 8; ++mask) {
            std::vector<std::string> modifiers;
            fcitx::KeyStates states;
            for (int i = 0; i < 3; ++i) {
                if (mask & (1 << i)) {
                    modifiers.push_back(modifierStates[i].first);
                    states |= modifierStates[i].second;
                }
            }
            auto key = textbridge::makeForwardKey(name, modifiers);
            if (!key || key->sym() != sym || key->states() != states) {
                throw std::runtime_error("Incorrect forwarded key or modifiers: " + name + " mask=" + std::to_string(mask));
            }
        }
    }
    for (const auto &name : {"", "a", "Ctrl", "Control_L", "F0", "F01", "F13", "10", "Return+X", "Super_L"}) {
        if (textbridge::makeForwardKey(name, {})) {
            throw std::runtime_error(std::string("Unexpected allowed key: ") + name);
        }
    }
    if (textbridge::makeForwardKey("Return", {"Super"})) {
        throw std::runtime_error("Unexpected allowed modifier");
    }
    std::cout << "Verified " << keys.size() << " keys with all 8 modifier combinations\n";
}
