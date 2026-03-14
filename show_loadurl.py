from pathlib import Path
p = Path(r'c:/Users/Zeehan/Documents/VSCode/DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/AMLLLyricsView.kt')
lines = p.read_text(encoding='utf-8').splitlines()
idx = [i for i, l in enumerate(lines) if 'loadUrl(' in l and 'appassets' in l]
print(idx)
if idx:
    i = idx[0]
    print('\n'.join(lines[i-2:i+3]))
