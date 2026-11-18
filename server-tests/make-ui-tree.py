#!/usr/bin/env python3
"""Создаёт демо-дерево файлов для браузерных тестов (server-tests/ui-test.js).

Использование: python3 server-tests/make-ui-tree.py /tmp/pocketbridge-ui/root
Требуются Pillow и python-docx:  pip install pillow python-docx
Опционально ffmpeg — для генерации воспроизводимого Видео/sample.mp4.
"""
import math
import os
import random
import shutil
import struct
import subprocess
import sys
import wave

from PIL import Image, ImageDraw
import docx

root = sys.argv[1] if len(sys.argv) > 1 else '/tmp/pocketbridge-ui/root'
for d in ['Фото/Отпуск', 'Документы', 'Музыка', 'Видео', 'Загрузки', 'Много файлов', 'Длинные имена']:
    os.makedirs(os.path.join(root, d), exist_ok=True)

random.seed(1)
for i in range(6):
    img = Image.new('RGB', (1600, 1200), (20 + i * 25, 40, 60))
    draw = ImageDraw.Draw(img)
    for _ in range(60):
        x, y = random.randint(0, 1600), random.randint(0, 1200)
        r = random.randint(10, 200)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=(random.randint(0, 255), random.randint(0, 255), random.randint(0, 255)))
    img.save(os.path.join(root, 'Фото/Отпуск', f'IMG_20{i:02d}.jpg'), quality=88)
Image.open(os.path.join(root, 'Фото/Отпуск/IMG_2000.jpg')).resize((400, 300)).save(os.path.join(root, 'Фото', 'обложка.png'))

with open(os.path.join(root, 'Фото', 'иконка.svg'), 'w', encoding='utf-8') as f:
    f.write(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 90" width="120" height="90">'
        '<rect width="120" height="90" rx="14" fill="#102c27"/>'
        '<circle cx="60" cy="45" r="26" fill="#75e3bd"/>'
        '<script>alert("SVG JS ВЫПОЛНИЛСЯ")</script></svg>'
    )

with open(os.path.join(root, 'Документы', 'заметки.md'), 'w', encoding='utf-8') as f:
    f.write('# Заметки\n\n- пункт один\n- пункт два\n\n**жирный** текст.\n' * 50)
with open(os.path.join(root, 'Документы', 'контакты.csv'), 'w', encoding='utf-8') as f:
    f.write('имя,телефон\nИван,123\nМария,456\n')
doc = docx.Document()
doc.add_heading('Отчёт по проекту', 0)
for j in range(30):
    doc.add_paragraph(f'Абзац {j + 1}: проверка извлечения текста из DOCX. ' * 3)
doc.save(os.path.join(root, 'Документы', 'отчёт.docx'))
with open(os.path.join(root, 'Документы', 'большой-текст.txt'), 'w', encoding='utf-8') as f:
    f.write('Строка текста для проверки лимита 5 МБ.\n' * 140000)
with open(os.path.join(root, 'Документы', 'страница.html'), 'w', encoding='utf-8') as f:
    f.write(
        '<!doctype html><html><body><h1>HTML из телефона</h1><script>alert("JS ВЫПОЛНИЛСЯ")</script>'
        '<img src="http://example.com/logo.png"><img src="https://evil.example/x.png">'
        '<p>Скрипты и внешние картинки должны быть заблокированы.</p></body></html>'
    )

pdf = b"""%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj
4 0 obj<</Length 60>>stream
BT /F1 24 Tf 72 760 Td (Test PDF from phone) Tj ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
trailer<</Root 1 0 R>>
%%EOF"""
with open(os.path.join(root, 'Документы', 'инструкция.pdf'), 'wb') as f:
    f.write(pdf)

sample_rate = 44100
duration = 6
with wave.open(os.path.join(root, 'Музыка', 'трек.wav'), 'w') as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(sample_rate)
    frames = bytearray()
    for n in range(sample_rate * duration):
        value = int(12000 * math.sin(2 * math.pi * 440 * n / sample_rate) * math.exp(-n / (sample_rate * 4)))
        frames += struct.pack('<h', value)
    w.writeframes(bytes(frames))
with open(os.path.join(root, 'Музыка', 'битый.mp3'), 'wb') as f:
    f.write(b'ID3\x04\x00\x00garbage' * 100)

video_path = os.path.join(root, 'Видео', 'sample.mp4')
made_video = False
if shutil.which('ffmpeg'):
    res = subprocess.run(
        [
            'ffmpeg', '-y', '-loglevel', 'error',
            '-f', 'lavfi', '-i', 'testsrc=duration=2:size=320x240:rate=15',
            '-c:v', 'libvpx-vp9', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
            video_path,
        ],
        check=False,
    )
    made_video = res.returncode == 0 and os.path.isfile(video_path) and os.path.getsize(video_path) > 0
if not made_video:
    # Минимальный MP4-контейнер (ftyp + пустой mdat), если ffmpeg не установлен
    ftyp = b'isom\x00\x00\x02\x00isomiso2mp41'
    with open(video_path, 'wb') as f:
        f.write(struct.pack('>I4s', 8 + len(ftyp), b'ftyp') + ftyp)
        f.write(struct.pack('>I4s', 16, b'mdat') + b'\x00' * 8)

with open(os.path.join(root, 'Загрузки', 'архив.zip'), 'wb') as f:
    f.write(os.urandom(3 * 1024 * 1024))
with open(os.path.join(root, 'Загрузки', 'большой.bin'), 'wb') as f:
    for _ in range(50):
        f.write(os.urandom(1024 * 1024))

for i in range(300):
    with open(os.path.join(root, 'Много файлов', f'file_{i:03d}.txt'), 'w', encoding='utf-8') as f:
        f.write(f'{i}\n')

with open(os.path.join(root, 'Длинные имена', 'очень-длинное-имя-файла-' + 'abcdefghij' * 12 + '.txt'), 'w', encoding='utf-8') as f:
    f.write('long\n')
with open(os.path.join(root, 'Длинные имена', '📱 эмодзи #1 & <tag> "quotes".txt'), 'w', encoding='utf-8') as f:
    f.write('uni\n')
print('Демо-дерево готово:', root)
