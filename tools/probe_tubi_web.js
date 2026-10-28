// One-off check: does a Tubi title page play in a plain Chromium window (like an in-app browser)?
const { chromium } = require('playwright');
const out = [];
const log = s => { out.push(s); console.log(s); };
(async () => {
  const cat = require('../catalog/tubi.json');
  const sup = cat.rows.find(r => r.name === 'Superheroes') || cat.rows[0];
  const items = sup.items.slice(0, 2).map(i => cat.items[i]).filter(x => x.k === 'movie');
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  for (const ua of ['Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36',
                    'Mozilla/5.0 (Linux; Android 11; AFTKA Build/RS8101; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0 Mobile Safari/537.36']) {
    const ctx = await b.newContext({ userAgent: ua, viewport: { width: 1280, height: 720 } });
    const p = await ctx.newPage();
    const it = items[0];
    log(`--- UA ${ua.includes('wv') ? 'Android WebView' : 'desktop'}  title: ${it.t}  ${it.u}`);
    const media = [];
    p.on('request', r => { const u = r.url(); if (/\.m3u8|\.mpd|manifest|license|widevine|\.ts\b|\.m4s/i.test(u)) media.push(u.slice(0, 110)); });
    try {
      await p.goto(it.u, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await p.waitForTimeout(6000);
      log('final url: ' + p.url());
      const btns = await p.$$eval('button, a', els => els.map(e => (e.innerText || e.getAttribute('aria-label') || '').trim()).filter(t => /play|watch|start|resume|sign/i.test(t)).slice(0, 10));
      log('play-ish buttons: ' + JSON.stringify(btns));
      const playLinks = await p.$$eval('a[href]', els => els.map(e => e.getAttribute('href')).filter(h => /player|watch|start=|\/video/i.test(h)).slice(0, 5));
      log('player links: ' + JSON.stringify(playLinks));
      const btn = p.getByRole('button', { name: /play|watch|start/i }).first();
      if (await btn.count()) { await btn.click({ timeout: 5000 }).catch(e => log('click err ' + e.message.slice(0, 80))); }
      else {
        const a = p.getByRole('link', { name: /play|watch|start/i }).first();
        if (await a.count()) await a.click({ timeout: 5000 }).catch(e => log('click err ' + e.message.slice(0, 80)));
      }
      await p.waitForTimeout(20000);
      log('after click url: ' + p.url());
      const v = await p.$$eval('video', vs => vs.map(v => ({ src: (v.currentSrc || '').slice(0, 80), t: +v.currentTime.toFixed(1), paused: v.paused, w: v.videoWidth, err: v.error && v.error.code })));
      log('videos: ' + JSON.stringify(v));
      const txt = (await p.innerText('body')).replace(/\s+/g, ' ').slice(0, 300);
      log('page text: ' + txt);
      log('media requests: ' + media.length + ' ' + JSON.stringify(media.slice(0, 6)));
    } catch (e) { log('error ' + e.message.slice(0, 200)); }
    await ctx.close();
  }
  await b.close();
  console.log('::notice::TUBI WEB PROBE%0A' + out.join('%0A').replace(/\r?\n/g, ' '));
})();
