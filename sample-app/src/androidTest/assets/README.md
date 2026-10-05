# Synthetic replay video

`replay-clock.mp4` is a six-second, silent 320×240 H.264 fixture: red for seconds 0–2, green for
2–4, blue for 4–6. It contains no phone capture, application data or real recording. Android replay
checks compare actual rendered pixels with timeline/media offsets, including backward seeks and
fullscreen surface recreation. Normal QA capture does not depend on ffmpeg.

Regenerate deliberately with an installed ffmpeg:

```sh
ffmpeg -f lavfi -i "color=c=red:s=320x240:r=10:d=6" \
  -vf "drawbox=x=0:y=0:w=iw:h=ih:color=green:t=fill:enable='gte(t,2)*lt(t,4)',drawbox=x=0:y=0:w=iw:h=ih:color=blue:t=fill:enable='gte(t,4)'" \
  -an -c:v libx264 -pix_fmt yuv420p -g 10 -movflags +faststart \
  sample-app/src/androidTest/assets/replay-clock.mp4
```
