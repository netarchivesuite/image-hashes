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

Both hash algorithms were benchmarked against independent reference implementations on the same test image ( 1070×700 png), on the same machine(13th Gen Intel(R) Core(TM) i9-13900K), with a warmed-up JVM (JIT-compiled before timing) and Python's time.perf_counter().
Performance is identical for JPEG images of the same dimensions. The relative ranking between implementations also stays consistent across different image sizes — larger images are slower for all implementations, but the ratios between them remain the same, so the table below is representative regardless of the image resolution in your corpus.

| Implementation | Algorithm | ms/image | Throughput |
|---|---|---|---|
| image-hashes (Java) | pdqHash | 6.1 ms | 163.6 img/sec |
| image-hashes (Java) | pdqHash (8 dihedral variants) | 6.1 ms | 163.9 img/sec |
| image-hashes (Java) | pHash | 10.8 ms | 93.0 img/sec |
| phim (Python/Rust) | pdqHash (8 dihedral variants) | 9.1 ms | 110.2 img/sec |
| phim (Python/Rust) | pHash | 4.1 ms | 242.1 img/sec |
| Meta official (Java) | pdqHash (naive) | 16.3 ms | 61.3 img/sec |
| Meta official (Java) | pdqHash (pre-allocated) | 15.6 ms | 63.9 img/sec |

### Notes

- **PDQ**: this library is **2.6x faster** than Meta's own official Java reference implementation. The gap is explained by a single optimization: direct `DataBufferByte` pixel extraction, bypassing the per-pixel `getRGB(x, y)` call that Meta's implementation still uses. This library is also **1.5x faster** than phim's Rust-backed Python implementation.
- **PDQ dihedral variants**: computing all 8 rotation/mirror variants via `getAllDihedralHashes()` costs essentially the same as computing a single hash — both pay the same dominant cost (pixel extraction, Jarosz filter, 2D DCT) exactly once. The 7 extra variants are derived cheaply from the same 16×16 2D DCT buffer without re-running the pipeline.
- **pHash**: phim is **2.6x faster** than this library. phim's pHash is backed by a compiled Rust core (not pure Python) with a likely FFT-based 2D DCT, versus this library's straightforward O(n²) 2D DCT — closing this gap further would require adopting a similar FFT-based approach.
- **Meta's buffer pre-allocation**: Meta's `fromBufferedImage()` API is designed for callers to pre-allocate and reuse scratch buffers across calls. In practice this makes almost no difference (16.3ms naive vs 15.6ms pre-allocated) because the dominant cost is the per-pixel `getRGB()` loop, not buffer allocation.
- **No official Meta/Facebook Java implementation of pHash exists** — unlike PDQ, pHash has no single canonical reference, so that cell is left blank.
- Per-image timings are averages over 1000 hash computations, with all implementations confirmed to produce byte-for-byte identical hashes before timing.


### Lanczos resampling compatibility
The pHash algorithm resizes the source image to 32×32 pixels using a Lanczos filter before computing the 2D DCT. Lanczos is not a single deterministic standard — different libraries make different implementation choices and produce different pixel values, resulting in incompatible hashes from the same source image.
This library produces byte-for-byte identical hashes to phim, as both use the same Lanczos variant.
The following libraries produce different, incompatible hashes: OpenCV, TwelveMonkeys and scikit-image.