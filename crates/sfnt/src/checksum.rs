/// The OpenType table checksum: the wrapping sum of the data read as big-endian `u32` words,
/// with the final partial word padded by zero bytes.
///
/// Data can be fed in chunks of any size; the result is the same as for the concatenated data.
#[derive(Clone, Copy, Default)]
pub struct Checksum {
    sum: u32,
    word: u32,
    filled: usize,
}

impl Checksum {
    pub fn new() -> Checksum {
        Checksum::default()
    }

    pub fn of(data: &[u8]) -> u32 {
        let mut checksum = Checksum::new();
        checksum.update(data);
        checksum.value()
    }

    pub fn update(&mut self, mut data: &[u8]) {
        while self.filled != 0 && !data.is_empty() {
            self.push(data[0]);
            data = &data[1..];
        }
        let (words, rest) = data.as_chunks::<4>();
        for word in words {
            self.sum = self.sum.wrapping_add(u32::from_be_bytes(*word));
        }
        for byte in rest {
            self.push(*byte);
        }
    }

    pub fn value(&self) -> u32 {
        if self.filled == 0 { self.sum } else { self.sum.wrapping_add(self.word << (8 * (4 - self.filled))) }
    }

    pub fn push(&mut self, byte: u8) {
        self.word = (self.word << 8) | u32::from(byte);
        self.filled += 1;
        if self.filled == 4 {
            self.sum = self.sum.wrapping_add(self.word);
            self.word = 0;
            self.filled = 0;
        }
    }
}
