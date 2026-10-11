use std::fmt;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// A case-sensitive wildcard pattern: `*` matches any sequence of characters (including none),
/// `?` matches exactly one character, and every other character matches itself.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct Pattern {
    text: String,
}

impl Pattern {
    pub fn new(text: impl Into<String>) -> Pattern {
        Pattern { text: text.into() }
    }

    pub fn text(&self) -> &str {
        &self.text
    }

    /// Whether the pattern contains no wildcard, so that it matches only one exact name.
    pub fn is_exact(&self) -> bool {
        !self.text.contains(['*', '?'])
    }

    pub fn matches(&self, name: &str) -> bool {
        let pattern: Vec<char> = self.text.chars().collect();
        let name: Vec<char> = name.chars().collect();
        // Iterative matching with backtracking to the last `*`: linear in practice, and never
        // exponential, whatever the pattern.
        let (mut p, mut n) = (0, 0);
        let mut star: Option<(usize, usize)> = None;
        while n < name.len() {
            if p < pattern.len() && (pattern[p] == '?' || pattern[p] == name[n]) {
                p += 1;
                n += 1;
            } else if p < pattern.len() && pattern[p] == '*' {
                star = Some((p, n));
                p += 1;
            } else if let Some((star_p, star_n)) = star {
                p = star_p + 1;
                n = star_n + 1;
                star = Some((star_p, star_n + 1));
            } else {
                return false;
            }
        }
        pattern[p..].iter().all(|character| *character == '*')
    }
}

impl fmt::Debug for Pattern {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "Pattern({:?})", self.text)
    }
}

impl fmt::Display for Pattern {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.text)
    }
}

impl Serialize for Pattern {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.text)
    }
}

impl<'de> Deserialize<'de> for Pattern {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Pattern, D::Error> {
        Ok(Pattern::new(String::deserialize(deserializer)?))
    }
}
