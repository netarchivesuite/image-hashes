# Image Hashing (pHash & PDQ)

Pure-Java, dependency-free implementations of two perceptual image hashing
algorithms, used for finding visually similar images at scale in the
netarchive:

- **`PdqHasher`** — Meta's [PDQ hash](https://github.com/facebook/ThreatExchange/tree/main/pdq), a 256-bit perceptual hash.
- **`PhashHasher`** — the classic DCT-based pHash, 64-bit, compatible with the
  [phim](https://github.com/NationalLibraryOfNorway/webdata-wp3-phim) Python/Rust reference implementation.

Both implementations were validated byte-for-byte against their respective
reference implementations (Meta's C++ `pdqhash` and the National Library of
Norway's `phim`) — see `PdqHasherTest` and `PhashHasherTest`.

## Known limitations: what these hashes can and can't detect

Both algorithms are designed to be robust to *re-encoding* (resizing, JPEG
recompression, mild noise) but are **not** designed to be robust to
*geometric* transforms (rotation, mirroring) or to **aggressive cropping**.
The table below demonstrates this directly using a single source photo run
through twelve transforms.

A Hamming distance close to 0 means "the algorithm considers these the same
image." A large Hamming distance means "the algorithm does not consider
these similar" — for PDQ, the conventional similarity threshold is
**≤ 31** (out of 256 bits); for pHash, a commonly used threshold is
**≤ 10** (out of 64 bits). Distances above those thresholds are shown in
*italics* below to flag the cases where similarity detection is expected to
fail.

| Thumbnail | Variant | pHash (hex) | pHash distance | PDQ (hex) | PDQ distance |
|---|---|---|---|---|---|
| ![original](readme_thumbs/cat_01_original_thumb.jpg) | Original | `d29499b7a706236b` | 0 | `7c744d5ce9c82d4ec65e6b138dd63b9699966644a360e1e1b165da19b6295c4b` | 0 |
| ![downscaled](readme_thumbs/cat_02_downscaled_thumb.jpg) | Downscaled to 25% | `d29499b7a706236b` | 0 | `7c74cd5c2948af4ec6536b138f963b9699d46644a360e1c1b16dda19f6295c4b` | 16 |
| ![grayscale](readme_thumbs/cat_03_grayscale_thumb.jpg) | Grayscale | `d29499b7a706236b` | 0 | `7c744d5ca9c8ad4ec65e6b138dd63b9699966644a360e1e1b165da19b6295c4b` | 2 |
| ![rotated45](readme_thumbs/cat_04_rotated45_thumb.jpg) | Rotated 45° | `664e98b93103ce7e` | *28* | `413c46332f43b966707e61cc6781470fcc7f6c7ba9c0b1ccb09d931927726e66` | *114* |
| ![rotated90](readme_thumbs/cat_05_rotated90_thumb.jpg) | Rotated 90° | `0781ecf0de5ce11b` | *32* | `3abe33aab57a7e70f78525bc1f66884723d08587803a307b78072c371f81fbe0` | *136* |
| ![rotated180](readme_thumbs/cat_06_rotated180_thumb.jpg) | Rotated 180° | `873ecd0cd2ac76c1` | *34* | `0961e7f63c9d07e4930bc1b9d803913cccc3cc6ef6354b4be43070b3637cf6a1` | *130* |
| ![mirrored](readme_thumbs/cat_07_mirrored_thumb.jpg) | Mirrored (horizontal flip) | `7a3e321d2dac89c3` | *30* | `29611809fc9dfa1b930f3e46da836ec3ccc33391f635b4b4e4308f4ce37c095e` | *128* |
| ![noise](readme_thumbs/cat_08_noise_thumb.jpg) | Visible random noise | `d29499b7a706236b` | 0 | `7c744d5ca9c82d4ec65e6b138dd63b9699966644a360e1e1b165da19f6295c4b` | 2 |
| ![text opaque](readme_thumbs/cat_09_text_opaque_thumb.jpg) | Opaque caption bar | `d0949da78726276b` | 6 | `5c166d7da9ce2c58c65f6d708f963f9619966744e360e1e121659ab97c29940b` | 40 |
| ![text overlay](readme_thumbs/cat_10_text_overlay_thumb.jpg) | Translucent watermark | `d29499a7a726236b` | 2 | `5c344d55e9c8ad5ec6576b138dd63b9699946644a360e1e1b165da19f6295c4b` | 10 |
| ![jpeg low quality](readme_thumbs/cat_11_jpeg_lowquality_thumb.jpg) | Heavy JPEG recompression (q=15) | `d29499b7a706236b` | 0 | `5c744d5ca9c8ad4ec65e6b138dd63b96999666c4a360e1e1b165da19b6295c4b` | 4 |
| ![cropped](readme_thumbs/cat_12_cropped_thumb.jpg) | Cropped 15% off each edge | `77dfdc91d1868484` | *32* | `6a580a90e615a4b4c684788c1a0dfaec7e25f6a17ee5399b19d9813b94fb52ee` | *130* |

### Takeaways

- **Re-encoding transforms** (downscale, grayscale, noise, JPEG
  recompression, translucent watermark) all stay comfortably under both
  similarity thresholds — these are the cases the hashes are designed for.
- **Geometric transforms** (any rotation, mirroring) and **aggressive
  cropping** push both hashes well past their thresholds. This is expected:
  neither algorithm is rotation-, mirror-, or crop-invariant by design.
  Cropping is notable because, unlike rotation, it's a very common
  real-world transform in netarchive material (thumbnails, re-published
  excerpts) — so a 15% crop here behaves like a full 90° rotation in terms
  of detectability.
- **An opaque caption bar** costs more distance than a **translucent
  watermark** covering a similar area, since the opaque version destroys
  the underlying pixel information entirely rather than just attenuating it.

See `PdqHasherCatImageSetTest.java` and `PhashHasherCatImageSetTest.java`
for the full test suite that generated these numbers.



## Performance comparison across implementations

Both hash algorithms were benchmarked against independent reference
implementations on the same two test images, on the same machine, with a
warmed-up JVM (JIT-compiled before timing) and Python's `time.perf_counter()`.
Every comparison below was correctness-checked first — all implementations
produced byte-for-byte identical hashes before any timing was trusted.

| Algorithm | Image | Pixel size | This library (Java) | Meta official (Java) | phim (Python/Rust) |
|---|---|---|---|---|---|
| PDQ | cat_nubbe.jpg | 1245×934 | 43.5 ms/image | 71.1 ms/image | 57.2 ms/image |
| PDQ | maria.png | 1070×700 | 12.7 ms/image | 30.9 ms/image | 21.9 ms/image |
| pHash | cat_nubbe.jpg | 1245×934 | 47.4 ms/image |  | 10.9 ms/image |
| pHash | maria.png | 1070×700 | 29.5 ms/image |  | 7.3 ms/image |

### Notes

- **PDQ**: this library is **1.5-2.6x faster** than Meta's own official Java
  reference implementation, and **1.3-1.8x faster** than phim's
  Rust-backed Python implementation.
- **pHash**: phim is **4-4.3x faster** than this library. phim's pHash is
  backed by a compiled Rust core (not pure Python) with a likely FFT-based
  DCT, versus this library's straightforward O(n²) DCT — closing this gap
  further would require a similar algorithmic change.
- No official Meta/Facebook Java implementation of pHash exists — unlike
  PDQ, pHash has no single canonical reference, so that cell is left blank.
- Per-image timings are averages over 100-1000 hash computations per cell,
  with all implementations confirmed to produce identical hashes before
  timing.
