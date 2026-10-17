#include "keyactions.h"

namespace textbridge {
namespace {

std::optional<std::string> keyToFcitxName(const std::string &key) {
    if (key == "Space") {
        return "space";
    }
    if (key.size() == 1 && key[0] >= 'A' && key[0] <= 'Z') {
        return std::string(1, static_cast<char>(key[0] - 'A' + 'a'));
    }
    if (key.size() == 1 && key[0] >= '0' && key[0] <= '9') {
        return key;
    }
    if ((key.size() == 2 && key[0] == 'F' && key[1] >= '1' && key[1] <= '9') ||
        key == "F10" || key == "F11" || key == "F12") {
        return key;
    }
    if (key == "Return" || key == "Escape" || key == "Tab" || key == "BackSpace" ||
        key == "Delete" || key == "Left" || key == "Right" || key == "Up" ||
        key == "Down" || key == "Home" || key == "End" || key == "Page_Up" ||
        key == "Page_Down") {
        return key;
    }
    return std::nullopt;
}

std::optional<fcitx::Key> parseForwardKey(const std::string &keyName,
                                          const std::vector<std::string> &modifiers,
                                          const std::string &controlName) {
    auto name = keyToFcitxName(keyName);
    if (!name) {
        return std::nullopt;
    }
    std::string spec;
    for (const auto &modifier : modifiers) {
        if (modifier == "Control") {
            spec += controlName;
        } else if (modifier == "Shift" || modifier == "Alt") {
            spec += modifier;
        } else {
            return std::nullopt;
        }
        spec += "+";
    }
    spec += *name;
    fcitx::Key key(spec);
    if (!key.isValid()) {
        return std::nullopt;
    }
    return key;
}

} // namespace

std::optional<fcitx::Key> makeForwardKey(const std::string &keyName,
                                         const std::vector<std::string> &modifiers) {
    auto key = parseForwardKey(keyName, modifiers, "Control");
    if (key) {
        return key;
    }
    return parseForwardKey(keyName, modifiers, "Ctrl");
}

} // namespace textbridge
