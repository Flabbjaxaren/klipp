// SPDX-License-Identifier: Apache-2.0
//
// Minimal JSON reader for container/frame metadata (no dependencies).
// Numbers are doubles; malformed input throws std::runtime_error.
#pragma once

#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace craw::json {

struct Value {
    enum class Type { Null, Bool, Number, String, Array, Object } type = Type::Null;
    bool b = false;
    double n = 0;
    std::string s;
    std::vector<Value> a;
    std::map<std::string, Value> o;

    bool isNull() const { return type == Type::Null; }
    bool isNumber() const { return type == Type::Number; }
    bool isArray() const { return type == Type::Array; }
    bool isObject() const { return type == Type::Object; }
    bool isString() const { return type == Type::String; }

    // Member lookup; returns a static null for missing keys or non-objects.
    const Value& operator[](const std::string& key) const {
        static const Value null;
        if (type != Type::Object) return null;
        const auto it = o.find(key);
        return it == o.end() ? null : it->second;
    }
    const Value& operator[](size_t i) const {
        static const Value null;
        return type == Type::Array && i < a.size() ? a[i] : null;
    }
    size_t size() const { return type == Type::Array ? a.size() : 0; }
    double num(double fallback = 0) const { return type == Type::Number ? n : fallback; }
    std::string str(const std::string& fallback = {}) const { return type == Type::String ? s : fallback; }
};

class Parser {
public:
    explicit Parser(const std::string& text) : t_(text) {}

    Value parse() {
        Value v = value();
        ws();
        if (p_ != t_.size()) fail("trailing characters");
        return v;
    }

private:
    [[noreturn]] void fail(const char* what) const {
        throw std::runtime_error(std::string("json: ") + what + " at " + std::to_string(p_));
    }
    void ws() {
        while (p_ < t_.size() && (t_[p_] == ' ' || t_[p_] == '\t' || t_[p_] == '\n' || t_[p_] == '\r')) ++p_;
    }
    char peek() {
        ws();
        if (p_ >= t_.size()) fail("unexpected end");
        return t_[p_];
    }
    void expect(char c) {
        if (peek() != c) fail("unexpected character");
        ++p_;
    }
    Value value() {
        if (++depth_ > 64) fail("nesting too deep");
        Value v;
        const char c = peek();
        if (c == '{') {
            v.type = Value::Type::Object;
            ++p_;
            if (peek() != '}') {
                for (;;) {
                    std::string k = string();
                    expect(':');
                    v.o[k] = value();
                    if (peek() == ',') { ++p_; continue; }
                    break;
                }
            }
            expect('}');
        } else if (c == '[') {
            v.type = Value::Type::Array;
            ++p_;
            if (peek() != ']') {
                for (;;) {
                    v.a.push_back(value());
                    if (peek() == ',') { ++p_; continue; }
                    break;
                }
            }
            expect(']');
        } else if (c == '"') {
            v.type = Value::Type::String;
            v.s = string();
        } else if (t_.compare(p_, 4, "true") == 0) {
            v.type = Value::Type::Bool; v.b = true; p_ += 4;
        } else if (t_.compare(p_, 5, "false") == 0) {
            v.type = Value::Type::Bool; p_ += 5;
        } else if (t_.compare(p_, 4, "null") == 0) {
            p_ += 4;
        } else {
            size_t used = 0;
            try {
                v.n = std::stod(t_.substr(p_, 40), &used);
            } catch (...) {
                fail("bad number");
            }
            if (used == 0) fail("bad number");
            v.type = Value::Type::Number;
            p_ += used;
        }
        --depth_;
        return v;
    }
    std::string string() {
        expect('"');
        std::string out;
        while (p_ < t_.size() && t_[p_] != '"') {
            char c = t_[p_++];
            if (c == '\\') {
                if (p_ >= t_.size()) fail("bad escape");
                const char e = t_[p_++];
                switch (e) {
                    case 'n': out += '\n'; break;
                    case 't': out += '\t'; break;
                    case 'r': out += '\r'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'u': {
                        if (p_ + 4 > t_.size()) fail("bad escape");
                        const unsigned cp = std::stoul(t_.substr(p_, 4), nullptr, 16);
                        p_ += 4;
                        if (cp < 0x80) out += static_cast<char>(cp);
                        else if (cp < 0x800) { out += char(0xC0 | (cp >> 6)); out += char(0x80 | (cp & 0x3F)); }
                        else { out += char(0xE0 | (cp >> 12)); out += char(0x80 | ((cp >> 6) & 0x3F)); out += char(0x80 | (cp & 0x3F)); }
                        break;
                    }
                    default: out += e;
                }
            } else {
                out += c;
            }
        }
        if (p_ >= t_.size()) fail("unterminated string");
        ++p_;
        return out;
    }

    const std::string& t_;
    size_t p_ = 0;
    int depth_ = 0;
};

inline Value parse(const std::string& text) { return Parser(text).parse(); }

// Parses, returning an empty object on malformed input (metadata is advisory).
inline Value parseOrEmpty(const std::string& text) {
    try {
        return parse(text);
    } catch (...) {
        Value v;
        v.type = Value::Type::Object;
        return v;
    }
}

}  // namespace craw::json
