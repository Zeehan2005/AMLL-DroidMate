import pathlib
import jsbeautifier

path = pathlib.Path('app/src/main/assets/amll/amll.bundle.js')
text = path.read_text(encoding='utf-8')
opts = jsbeautifier.default_options()
opts.indent_size = 2
opts.max_preserve_newlines = 2
opts.preserve_newlines = True
opts.break_chained_methods = True
beaut = jsbeautifier.beautify(text, opts)
path.write_text(beaut, encoding='utf-8')
print('Beautified', path)
