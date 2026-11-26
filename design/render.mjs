import { Resvg } from '@resvg/resvg-js';
import fs from 'node:fs';
const svg = fs.readFileSync('ic_launcher_master.svg', 'utf8');
for (const size of [512, 192, 1024]) {
  fs.writeFileSync(`preview-${size}.png`, new Resvg(svg, { fitTo: { mode: 'width', value: size } }).render().asPng());
}
console.log('rendered');
