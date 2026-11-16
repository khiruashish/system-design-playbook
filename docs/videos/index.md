# Video Course

!!! abstract "Every chapter as a narrated video"
    1 videos, about 8 minutes in all. Each walks through its chapter: the
    concepts, tables, diagrams, code and the interview Q&A (question + 30-second answer), with English
    captions. Each chapter page also shows its video under the title. The MP4 files are served from the
    repository's `videos` branch.

## Coding Interview

1 videos · about 8 min

<div class="ssp-video-grid">

<figure class="ssp-video">
<video controls preload="none" playsinline poster="coding-interview/03-lfu-cache.jpg">
<source src="https://raw.githubusercontent.com/khiruashish/system-design-playbook/videos/coding-interview/03-lfu-cache.mp4" type="video/mp4">
<track kind="captions" srclang="en" label="English" src="coding-interview/03-lfu-cache.vtt" default>
</video>
<figcaption><strong>3. LFU Cache in O(1)</strong> · 8:20 · <a href="https://raw.githubusercontent.com/khiruashish/system-design-playbook/videos/coding-interview/03-lfu-cache.mp4">open MP4</a></figcaption>
</figure>

</div>

!!! tip "How these were made"
    Generated from the markdown pages by `tools/videos/make_videos.py` (slides rendered with Pillow,
    narration by the open-source Kokoro text-to-speech model, assembled with ffmpeg). Edit a page,
    re-run the script for that page, and its video updates.
