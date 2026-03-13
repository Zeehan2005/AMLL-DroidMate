const fs = require('fs');
const parser = require('@babel/parser');

const path = 'C:/Users/Zeehan/Documents/VSCode/DroidMate/frontend/src/main.jsx';
const code = fs.readFileSync(path, 'utf8');

try {
  parser.parse(code, {
    sourceType: 'module',
    plugins: ['jsx', 'classProperties', 'optionalChaining', 'nullishCoalescingOperator'],
  });
  console.log('parsed ok');
} catch (e) {
  console.error('parse error', e.message);
  if (e.loc) console.error('at', e.loc);
  process.exit(1);
}
