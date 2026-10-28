const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const script = fs.readFileSync('src/main/resources/static/js/city-story.js', 'utf8');

const city = overrides => ({
  id: 27,
  slug: 'nanyang',
  name: '南阳<城>',
  province: '河南省',
  tagline: '卧龙岗与汉画',
  description: '城市介绍 <script>alert(1)</script>',
  image: '/assets/nanyang-hero.jpg',
  visit_advice: '游览建议完整段落',
  travel_advice: '出行建议完整段落',
  food: '南阳美食介绍',
  food_source: 'https://example.org/source',
  ...overrides
});

const spot = (id, title, image) => ({ id, title, image });
const food = title => ({ id: 15, title, city_name: '南阳', category: '小吃', intro: '地方风味介绍', image: '', published: 1, favorite_count: 0 });

function makeRenderer(options = {}) {
  const selectedCity = options.city || city();
  const calls = [];
  const context = {
    state: { cities: options.missingCity ? [] : [selectedCity] },
    esc(value) {
      return String(value == null ? '' : value).replace(/[&<>"']/g, char => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
      }[char]));
    },
    icon: () => '',
    crumb: () => '<nav>breadcrumb</nav>',
    photoNote(item) {
      return String(item.image).includes('nanyang-medical')
        ? '<p class="photo-note">医圣祠资料照片，摄于 2005 年。当前参观场所面貌请查看官方资料。</p>'
        : '';
    },
    grid(items, type) { return '<div data-grid="' + type + '">' + items.length + '</div>'; },
    foodGrid(items) { return '<div data-food-grid>' + items.map(item => item.title).join('|') + '</div>'; },
    empty(title, text) { return '<div class="empty">' + title + text + '</div>'; },
    async api(url) {
      calls.push(url);
      if (options.failure) throw new Error('API failed');
      if (url.includes('type=spot')) return { total: options.spotTotal ?? (options.spots || []).length, items: options.spots || [] };
      if (url.includes('type=guide')) return { total: options.guideTotal ?? (options.guides || []).length, items: options.guides || [] };
      return { total: options.foodTotal ?? (options.foods || []).length, items: options.foods || [] };
    }
  };
  vm.createContext(context);
  vm.runInContext(script, context);
  return { render: slug => context.cityStoryPage(slug), calls };
}

function makeAnchorHarness({ hash = '#foods', reduced = false, valid = true, keyboardActivated = false } = {}) {
  const calls = { scroll: [], history: [], navigate: 0, render: 0 };
  const target = { scrollIntoView: options => calls.scroll.push(options) };
  const link = { getAttribute: () => hash };
  const page = { contains: node => valid && node === target };
  const context = {
    location: { pathname: '/destination/nanyang', search: '?from=test' },
    history: {
      state: { roamIndex: 17 },
      replaceState(state, title, url) { calls.history.push({ state, title, url }); }
    },
    window: { matchMedia: () => ({ matches: reduced }) },
    document: {
      addEventListener(type, handler) { if (type === 'click') context.clickHandler = handler; },
      querySelector: () => page,
      getElementById: id => id === 'foods' && valid ? target : null
    },
    navigate() { calls.navigate++; },
    render() { calls.render++; }
  };
  vm.createContext(context);
  vm.runInContext(script, context);
  const event = {
    target: { closest: selector => selector === '.city-story-nav a' ? link : null },
    detail: keyboardActivated ? 0 : 1,
    defaultPrevented: false,
    preventDefault() { this.defaultPrevented = true; }
  };
  context.clickHandler(event);
  return { calls, event };
}

async function run() {
  const twoPhotos = makeRenderer({
    spots: [spot(1, '医圣祠', '/assets/nanyang-medical-old.jpg'), spot(2, '重复封面', '/assets/nanyang-hero.jpg'), spot(3, '汉画馆', '/assets/a.jpg'), spot(4, '卧龙岗', '/assets/b.jpg')],
    spotTotal: 8,
    guides: [{ id: 9, title: '攻略' }],
    guideTotal: 5,
    foods: [food('南阳牛肉汤'), food('新野板面')],
    foodTotal: 4
  });
  const full = await twoPhotos.render('nanyang');
  assert.deepEqual(twoPhotos.calls, [
    '/api/search?type=spot&size=24&city=27',
    '/api/search?type=guide&size=24&city=27',
    '/api/foods?size=6&city=27'
  ]);
  assert.match(full, /city-story-collage--photos-2 city-story-collage--cover/);
  assert.match(full, /8 个景点/);
  assert.match(full, /5 篇攻略/);
  assert.match(full, /全部 4 道美食/);
  assert.match(full, /city-story-food-grid--items-2/);
  assert.match(full, /南阳牛肉汤\|新野板面/);
  assert.match(full, /医圣祠资料照片，摄于 2005 年/);
  assert.match(full, /城市介绍 &lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(full, /href="\/spot\/1"/);
  assert.match(full, /href="\/spot\/3"/);
  assert.doesNotMatch(full, /href="\/spot\/4"/);
  assert.match(full, /先了解游览节奏与交通衔接，再按自己的时间安排/);
  assert.doesNotMatch(full, /city-story-kicker|保留这座城市现有/);

  const onePhoto = makeRenderer({ spots: [spot(1, '景点一', '/assets/spot.jpg')] });
  const one = await onePhoto.render('nanyang');
  assert.match(one, /city-story-collage--photos-1 city-story-collage--cover/);
  assert.equal((one.match(/class="city-story-photo city-story-photo-/g) || []).length, 1);

  const noAttachments = makeRenderer({ spots: [spot(1, '无照片景点', '')] });
  const none = await noAttachments.render('nanyang');
  assert.match(none, /city-story-collage--photos-0 city-story-collage--cover/);
  assert.doesNotMatch(none, /class="city-story-photo city-story-photo-/);

  const noCover = makeRenderer({ city: city({ image: '' }), spots: [] });
  const placeholder = await noCover.render('nanyang');
  assert.match(placeholder, /city-story-collage--photos-0 city-story-collage--no-cover/);
  assert.match(placeholder, /city-story-cover-placeholder/);
  assert.doesNotMatch(placeholder, /src=""/);

  const noCoverOnePhoto = makeRenderer({ city: city({ image: '' }), spots: [spot(5, '有实景图的景点', '/assets/real-spot.jpg')] });
  const noCoverOne = await noCoverOnePhoto.render('nanyang');
  assert.match(noCoverOne, /city-story-collage--photos-1 city-story-collage--no-cover/);
  assert.match(noCoverOne, /href="\/spot\/5"/);
  assert.doesNotMatch(noCoverOne, /src=""/);

  const oneFood = makeRenderer({ foods: [food('单道美食')] });
  assert.match(await oneFood.render('nanyang'), /city-story-food-grid--items-1/);

  const empty = makeRenderer({ city: city({ image: '', visit_advice: '', travel_advice: '', food: '', food_source: '' }) });
  const emptyPage = await empty.render('nanyang');
  assert.match(emptyPage, /景点资料正在整理/);
  assert.match(emptyPage, /攻略资料正在整理/);
  assert.match(emptyPage, /美食资料正在整理/);
  assert.match(emptyPage, /行程建议正在整理/);
  assert.doesNotMatch(emptyPage, /src=""/);

  await assert.rejects(makeRenderer({ missingCity: true }).render('missing'), /这个目的地还没有收录/);
  await assert.rejects(makeRenderer({ failure: true }).render('nanyang'), /API failed/);

  const anchor = makeAnchorHarness({ reduced: false });
  assert.equal(anchor.event.defaultPrevented, true);
  assert.deepEqual(anchor.calls.history.map(item => item.url), ['/destination/nanyang?from=test#foods']);
  assert.deepEqual(anchor.calls.history[0].state, { roamIndex: 17 });
  assert.equal(anchor.calls.scroll.length, 1);
  assert.equal(anchor.calls.scroll[0].block, 'start');
  assert.equal(anchor.calls.scroll[0].behavior, 'smooth');
  assert.equal(anchor.calls.navigate + anchor.calls.render, 0);

  const keyboardAnchor = makeAnchorHarness({ reduced: true, keyboardActivated: true });
  assert.equal(keyboardAnchor.calls.scroll.length, 1);
  assert.equal(keyboardAnchor.calls.scroll[0].block, 'start');
  assert.equal(keyboardAnchor.calls.scroll[0].behavior, 'instant');
  assert.equal(keyboardAnchor.calls.navigate + keyboardAnchor.calls.render, 0);

  const unknownAnchor = makeAnchorHarness({ hash: '#not-a-section', valid: false });
  assert.equal(unknownAnchor.event.defaultPrevented, true);
  assert.equal(unknownAnchor.calls.history.length + unknownAnchor.calls.scroll.length, 0);
  assert.equal(unknownAnchor.calls.navigate + unknownAnchor.calls.render, 0);
  console.log('city-story renderer checks passed');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
