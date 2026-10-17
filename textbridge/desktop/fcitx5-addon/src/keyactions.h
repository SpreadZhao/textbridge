#pragma once

#include <fcitx-utils/key.h>
#include <optional>
#include <string>
#include <vector>

namespace textbridge {
std::optional<fcitx::Key> makeForwardKey(const std::string &keyName,
                                         const std::vector<std::string> &modifiers);
}
