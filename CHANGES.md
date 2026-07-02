# Changelog


## [1.1.0] - 2026-07-02

### Features
- `PdqHasher.splitIntoBands(String hash)` — splits a 64-character PDQ hash into 8 disjoint
  substrings of 8 characters each, ready for indexing in a multi-band Solr field for efficient
  approximate Hamming distance search at billion-image scale. See
  [Fast Search in Hamming Space with Multi-Index Hashing](https://www.cs.toronto.edu/~norouzi/research/papers/multi_index_hashing.pdf)
  (Norouzi et al., CVPR 2012) for the theoretical foundation.
- `PhashHasher.splitIntoBands(String hash)` — equivalent method for pHash, splitting the
  16-character hash into 2 substrings of 8 characters each. Note that pHash is less suited
  for band-based search than PDQ due to its shorter hash length — see Javadoc for details.

## [1.0.0] - 2026-07-01

First public release.

### Features
- `PdqHasher` — Meta's PDQ hash (256-bit), including all 8 dihedral variants (rotations and mirrors) derived in a single pipeline pass
- `PhashHasher` — classic 2D DCT-based pHash (64-bit), byte-for-byte compatible with the [phim](https://github.com/NationalLibraryOfNorway/webdata-wp3-phim) Python/Rust reference implementation
- Pure Java, no external dependencies

### Maven

```xml
<dependency>
    <groupId>io.github.netarchivesuite</groupId>
    <artifactId>image-hashes</artifactId>
    <version>1.0.0</version>
</dependency>
```