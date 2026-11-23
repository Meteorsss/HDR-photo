# Test-only video

`live-probe.mp4` is a synthetic two-second moving test pattern, generated locally with:

```sh
ffmpeg -f lavfi -i 'testsrc2=size=160x240:rate=15' -t 2 -c:v libx264 -pix_fmt yuv420p -movflags +faststart -an live-probe.mp4
```

No user photographs or third-party footage. Packaged only in the instrumentation APK.
