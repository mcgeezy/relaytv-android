#!/usr/bin/env node

/*
 * Capture RelayTV from a connected Android device and compose README artwork.
 *
 * Requires adb plus Playwright with Chromium installed. To compose previously
 * captured images, pass --main, --servers, and --share instead of --serial.
 */

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

function option(name, fallback = '') {
  const prefix = `--${name}=`;
  const value = process.argv.find((arg) => arg.startsWith(prefix));
  return value ? value.slice(prefix.length) : fallback;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function pngData(buffer) {
  return `data:image/png;base64,${buffer.toString('base64')}`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function adb(serial, args, encoding = 'utf8') {
  return execFileSync('adb', ['-s', serial, ...args], {
    encoding,
    maxBuffer: 32 * 1024 * 1024,
    stdio: encoding ? ['ignore', 'pipe', 'pipe'] : ['ignore', 'pipe', 'pipe'],
  });
}

function capture(serial) {
  return adb(serial, ['exec-out', 'screencap', '-p'], null);
}

function screenSize(serial) {
  const output = adb(serial, ['shell', 'wm', 'size']);
  const match = output.match(/Physical size:\s*(\d+)x(\d+)/);
  if (!match) throw new Error(`Unable to parse Android screen size: ${output}`);
  return { width: Number(match[1]), height: Number(match[2]) };
}

function tapServerAction(serial, size) {
  adb(serial, ['shell', 'uiautomator', 'dump', '/sdcard/relaytv-readme.xml']);
  const xml = adb(serial, ['exec-out', 'cat', '/sdcard/relaytv-readme.xml']);
  const node = xml.match(/<node[^>]*content-desc="Servers"[^>]*>/)?.[0] || '';
  const bounds = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  if (bounds) {
    const x = Math.round((Number(bounds[1]) + Number(bounds[3])) / 2);
    const y = Math.round((Number(bounds[2]) + Number(bounds[4])) / 2);
    adb(serial, ['shell', 'input', 'tap', String(x), String(y)]);
    return;
  }

  // AppCompat places the server action at roughly two thirds of the toolbar.
  adb(serial, [
    'shell', 'input', 'tap',
    String(Math.round(size.width * 0.66)),
    String(Math.round(size.height * 0.08)),
  ]);
}

async function captureScreens(serial) {
  const policy = adb(serial, ['shell', 'dumpsys', 'window', 'policy']);
  if (!policy.includes('showing=false')) {
    throw new Error('The Android device must be connected and unlocked.');
  }

  const size = screenSize(serial);
  adb(serial, ['shell', 'cmd', 'statusbar', 'collapse']);
  adb(serial, ['shell', 'am', 'start', '-n', 'pro.relaytv/.MainActivity']);
  await sleep(4000);
  const main = capture(serial);

  tapServerAction(serial, size);
  await sleep(1800);
  const servers = capture(serial);
  adb(serial, ['shell', 'input', 'keyevent', '4']);
  await sleep(700);

  adb(serial, [
    'shell', 'am', 'start',
    '-a', 'android.intent.action.SEND',
    '-t', 'text/plain',
    '--es', 'android.intent.extra.TEXT', 'https://example.com/video',
  ]);
  await sleep(2200);
  const share = capture(serial);
  adb(serial, ['shell', 'input', 'keyevent', '4']);
  adb(serial, ['shell', 'am', 'start', '-n', 'pro.relaytv/.MainActivity']);
  await sleep(900);
  adb(serial, ['shell', 'cmd', 'statusbar', 'expand-notifications']);
  await sleep(1500);
  const media = capture(serial);
  adb(serial, ['shell', 'cmd', 'statusbar', 'collapse']);

  return { main, servers, share, media };
}

function phoneHtml(image, eyebrow, label, detail) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    *{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden}
    body{position:relative;background:
      radial-gradient(circle at 22% 18%,rgba(39,201,255,.25),transparent 31%),
      radial-gradient(circle at 82% 84%,rgba(45,99,255,.30),transparent 36%),
      linear-gradient(145deg,#06101f,#071a30 58%,#050913);
      color:#f4f8ff;font-family:Inter,ui-sans-serif,system-ui,-apple-system,sans-serif}
    body:before{content:"";position:absolute;inset:0;opacity:.13;background-image:
      linear-gradient(rgba(255,255,255,.14) 1px,transparent 1px),
      linear-gradient(90deg,rgba(255,255,255,.14) 1px,transparent 1px);background-size:44px 44px}
    .copy{position:absolute;z-index:3;left:42px;top:38px;max-width:650px}
    .eyebrow{color:#67dcff;font-size:15px;font-weight:850;letter-spacing:.15em;text-transform:uppercase}
    .label{margin-top:7px;font-size:30px;font-weight:800;letter-spacing:-.04em}
    .detail{margin-top:8px;color:#adc4e4;font-size:15px;font-weight:600}
    .glow{position:absolute;left:150px;top:180px;width:460px;height:780px;border-radius:50%;background:rgba(38,141,255,.17);filter:blur(64px)}
    .phone{position:absolute;z-index:2;left:50%;bottom:-18px;width:450px;padding:11px;transform:translateX(-50%);
      border:1px solid rgba(255,255,255,.30);border-radius:58px;background:#02050a;
      box-shadow:0 40px 95px rgba(0,0,0,.62),0 0 0 5px rgba(87,191,255,.09)}
    .screen{display:block;width:428px;height:951px;border-radius:46px;object-fit:cover;object-position:top;background:#071426}
    .speaker{position:absolute;z-index:4;top:20px;left:50%;width:108px;height:25px;transform:translateX(-50%);border-radius:18px;background:#02050a}
  </style></head><body><div class="copy"><div class="eyebrow">${escapeHtml(eyebrow)}</div>
    <div class="label">${escapeHtml(label)}</div><div class="detail">${escapeHtml(detail)}</div></div>
    <div class="glow"></div><div class="phone"><div class="speaker"></div>
    <img class="screen" src="${pngData(image)}" alt=""></div></body></html>`;
}

function heroHtml(images, banner) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    *{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden}
    body{position:relative;background:
      radial-gradient(circle at 78% 13%,rgba(54,210,255,.23),transparent 28%),
      radial-gradient(circle at 15% 88%,rgba(45,95,245,.31),transparent 36%),
      linear-gradient(145deg,#050a13,#08172b 55%,#07101e);
      color:#fff;font-family:Inter,ui-sans-serif,system-ui,-apple-system,sans-serif}
    .grid{position:absolute;inset:0;opacity:.12;background-image:
      linear-gradient(rgba(255,255,255,.14) 1px,transparent 1px),
      linear-gradient(90deg,rgba(255,255,255,.14) 1px,transparent 1px);background-size:54px 54px}
    .brand{position:absolute;z-index:8;left:48px;top:35px;width:350px;filter:drop-shadow(0 12px 25px rgba(0,0,0,.38))}
    .copy{position:absolute;z-index:8;left:60px;bottom:42px}
    .tagline{font-size:28px;font-weight:820;letter-spacing:-.035em}
    .sub{margin-top:7px;color:#afd0ec;font-size:15px;font-weight:620}
    .pills{display:flex;gap:9px;margin-top:14px}.pill{padding:7px 11px;border:1px solid rgba(114,214,255,.28);border-radius:999px;background:rgba(7,23,43,.78);color:#c8f0ff;font-size:11px;font-weight:820;letter-spacing:.1em;text-transform:uppercase}
    .flare{position:absolute;z-index:1;right:95px;top:75px;width:760px;height:760px;border-radius:50%;background:rgba(36,149,255,.17);filter:blur(80px)}
    .phone{position:absolute;z-index:4;padding:8px;border:1px solid rgba(255,255,255,.28);border-radius:43px;background:#02050a;box-shadow:0 38px 85px rgba(0,0,0,.65),0 0 0 4px rgba(85,191,255,.08)}
    .phone img{display:block;width:100%;height:100%;border-radius:34px;object-fit:cover;object-position:top}
    .phone:before{content:"";position:absolute;z-index:5;top:15px;left:50%;width:77px;height:18px;transform:translateX(-50%);border-radius:12px;background:#02050a}
    .main{left:625px;top:54px;width:358px;height:796px;z-index:6}
    .servers{left:373px;top:144px;width:298px;height:662px;transform:rotate(-5deg)}
    .share{right:105px;top:136px;width:306px;height:680px;transform:rotate(5deg)}
  </style></head><body><div class="grid"></div><div class="flare"></div>
    <img class="brand" src="${pngData(banner)}" alt="RelayTV">
    <div class="phone servers"><img src="${pngData(images.servers)}" alt=""></div>
    <div class="phone main"><img src="${pngData(images.main)}" alt=""></div>
    <div class="phone share"><img src="${pngData(images.share)}" alt=""></div>
    <div class="copy"><div class="tagline">Your Android shortcut to the TV.</div>
      <div class="sub">Share media, control playback, and find every RelayTV on your network.</div>
      <div class="pills"><span class="pill">Play</span><span class="pill">Queue</span><span class="pill">Discover</span><span class="pill">Control</span></div>
    </div></body></html>`;
}

async function render(browser, html, outputPath, viewport) {
  const context = await browser.newContext({ viewport, deviceScaleFactor: 1 });
  const page = await context.newPage();
  try {
    await page.setContent(html, { waitUntil: 'load' });
    await page.waitForFunction(() => Array.from(document.images).every((image) => image.complete));
    await page.screenshot({ path: outputPath, type: 'png' });
  } finally {
    await context.close();
  }
}

async function main() {
  let chromium;
  try {
    ({ chromium } = require('playwright'));
  } catch (_error) {
    throw new Error('Playwright is required to compose images: npm install --no-save playwright');
  }

  const outputDir = path.resolve(option('output', 'docs/images/readme'));
  const bannerPath = path.resolve(option('banner', path.join(outputDir, 'relaytv-banner.png')));
  fs.mkdirSync(outputDir, { recursive: true });

  const supplied = {
    main: option('main'),
    servers: option('servers'),
    share: option('share'),
    media: option('media'),
  };
  const images = Object.values(supplied).every(Boolean)
    ? Object.fromEntries(Object.entries(supplied).map(([key, value]) => [key, fs.readFileSync(path.resolve(value))]))
    : await captureScreens(option('serial', execFileSync('adb', ['devices'], { encoding: 'utf8' })
      .split('\n').map((line) => line.trim().split(/\s+/)).find((parts) => parts[1] === 'device')?.[0] || ''));
  const banner = fs.readFileSync(bannerPath);

  const launchOptions = { headless: true };
  const executablePath = option('chromium', process.env.PLAYWRIGHT_CHROMIUM_PATH || '');
  if (executablePath) launchOptions.executablePath = executablePath;
  const browser = await chromium.launch(launchOptions);
  try {
    await render(browser, heroHtml(images, banner), path.join(outputDir, 'hero.png'), { width: 1600, height: 900 });
    await render(browser, phoneHtml(images.main, 'Control', 'Remote + queue', 'Everything you need from the couch.'), path.join(outputDir, 'remote-phone.png'), { width: 760, height: 1160 });
    await render(browser, phoneHtml(images.servers, 'Discover', 'Every server, one tap away', 'mDNS discovery, health, and per-server auth.'), path.join(outputDir, 'servers-phone.png'), { width: 760, height: 1160 });
    await render(browser, phoneHtml(images.share, 'Share', 'Play now or queue next', 'Two Android targets, no copy and paste.'), path.join(outputDir, 'share-phone.png'), { width: 760, height: 1160 });
    await render(browser, phoneHtml(images.media, 'System controls', 'Playback follows you', 'Lock screen and notification-shade controls.'), path.join(outputDir, 'media-controls-phone.png'), { width: 760, height: 1160 });
  } finally {
    await browser.close();
  }

  process.stdout.write(`${JSON.stringify({
    ok: true,
    outputDir,
    files: ['hero.png', 'remote-phone.png', 'servers-phone.png', 'share-phone.png', 'media-controls-phone.png'],
  }, null, 2)}\n`);
}

main().catch((error) => {
  process.stderr.write(`README screenshot generation failed: ${error.stack || error}\n`);
  process.exit(1);
});
