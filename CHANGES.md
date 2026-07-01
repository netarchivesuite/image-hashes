# Changelog

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