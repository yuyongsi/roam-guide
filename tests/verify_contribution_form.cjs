const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/static/js/contributions.js', 'utf8');
class FakeFormData {
  constructor(form) { this.form = form; }
  [Symbol.iterator]() { return Object.entries(this.form.values || {}).filter(([, value]) => value !== undefined).map(([key, value]) => [key, String(value)])[Symbol.iterator](); }
}
const context = {
  document: { getElementById: () => null, addEventListener: () => {} },
  state: { cities: [{ id: 7, province: '河南', name: '南阳' }], user: null, renderId: 1 },
  FormData: FakeFormData,
  URL, URLSearchParams, CSS: { escape: value => String(value) },
  esc: value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]),
  icon: () => '',
  console, setTimeout, clearTimeout, requestAnimationFrame: callback => callback(),
  location: { pathname: '/', search: '' },
};
vm.createContext(context);
vm.runInContext(source + `\nglobalThis.__formChecks = { contributionDefault, contributionFormFields, contributionSection, contributionCounter, contributionCanonical, contributionPayloadForSave, contributionValidationErrors, contributionDraftErrors, contributionReindexSections, contributionFocusField, contributionFormHtml, contributionPreviewHtml, contributionUi };`, context);
const api = context.__formChecks;

assert.equal(api.contributionDefault('spot').city_id, '', 'new CREATE forms must start without a selected city');
assert.match(api.contributionFormFields('spot', api.contributionDefault('spot'), 'CREATE'), /<option value="">选择公开目的地<\/option>/);
assert.match(api.contributionFormFields('spot', { ...api.contributionDefault('spot'), city_id:7 }, 'CREATE'), /<option value="7" selected>河南 · 南阳<\/option>/);

api.contributionUi.context = { spots:Array.from({length:6},(_,index)=>({id:index+1,title:'景点 '+(index+1)})) };
const selectedCitySpot = api.contributionFormFields('guide', { ...api.contributionDefault('guide'), city_id:3 }, 'CREATE');
assert.match(selectedCitySpot, /id="contribution-city-id-count">已选择地区/);
assert.match(selectedCitySpot, /data-contribution-spot-help>请选择至少一处同城公开景点/);
const missingReadOnly = api.contributionFormFields('guide', api.contributionDefault('guide'), 'CREATE', true);
assert.match(missingReadOnly, /contribution-city-id-count\">未设置地区/);
assert.doesNotMatch(missingReadOnly, /contribution-city-id-count\">请选择一个公开目的地/);
const selectedReadOnly = api.contributionFormFields('guide', { ...api.contributionDefault('guide'), city_id:3 }, 'CREATE', true);
assert.match(selectedReadOnly, /data-contribution-spot-help>本投稿关联的同城公开景点如下/);
assert.doesNotMatch(selectedReadOnly, /请选择至少一处同城公开景点/);
api.contributionUi.context = { spots:[] };
const noSpots = api.contributionFormFields('guide', { ...api.contributionDefault('guide'), city_id:3 }, 'CREATE');
assert.match(noSpots, /这个地区暂时没有公开景点/);

const updateHtml = api.contributionFormFields('guide', { ...api.contributionDefault('guide'), city_id: 7 }, 'UPDATE');
assert.match(updateHtml, /<select id="contribution-city-id" disabled/);
assert.match(updateHtml, /<input type="hidden" name="city_id" value="7">/);

const spotHtml = api.contributionFormFields('spot', api.contributionDefault('spot'), 'CREATE');
for (const [field, limit] of Object.entries({ title:100, intro:500, description:6000, highlights:3000, transportation:3000, tips:3000, map_query:200, source_url:1000 })) {
  assert.match(spotHtml, new RegExp(`name="${field}" maxlength="${limit}"`), `${field} maxlength must match the server`);
}
const foodHtml = api.contributionFormFields('food', api.contributionDefault('food'), 'CREATE');
assert.match(foodHtml, /name="category"/);
assert.match(foodHtml, /name="taste" maxlength="2000"/);
assert.match(foodHtml, /name="description" maxlength="6000"/);
assert.match(foodHtml, /上传资料图片 <span class="contribution-optional">选填/);
assert.match(spotHtml, /上传资料图片 <span class="contribution-required">必填/);
const guideHtml = api.contributionFormFields('guide', api.contributionDefault('guide'), 'CREATE');
assert.match(guideHtml, /name="subtitle" maxlength="150"/);
assert.match(guideHtml, /min="15" max="20160" step="1"/);
assert.match(api.contributionSection({}, 0), /maxlength="100"/);
assert.match(api.contributionSection({}, 0), /maxlength="10000"/);
assert.equal((api.contributionSection({}, 0).match(/id="contribution-section-0-(?:title|body)"/g) || []).length, 2);
assert.match(api.contributionCounter('x', '😀', 100), />2 \/ 100 字</, 'counter uses JavaScript UTF-16 string length');


function sectionModel(title, body) {
  const makeField = (suffix, value, maxLength) => {
    const counter = { id:'', textContent:'' }, error = { id:'' }, label = { htmlFor:'', querySelector: selector => selector.includes('counter') ? counter : error };
    const field = { id:'', value, maxLength, setAttribute(name, value) { this[name] = value; }, closest: () => label };
    return { field, label, counter, error };
  };
  const titleModel = makeField('title', title, 100), bodyModel = makeField('body', body, 10000), legend = { textContent:'' };
  const section = { querySelector(selector) { if (selector === 'legend') return legend; return selector.includes('title') ? titleModel.field : bodyModel.field; }, _parts:[legend,titleModel,bodyModel] };
  return section;
}
const sectionA = sectionModel('A','Body A'), sectionB = sectionModel('B','Body B');
const sectionHost = { querySelectorAll: () => [sectionA,sectionB] };
api.contributionReindexSections({ querySelector: () => sectionHost });
assert.equal(sectionA._parts[1].field.id, 'contribution-section-0-title');
assert.equal(sectionB._parts[1].field.id, 'contribution-section-1-title');
assert.equal(sectionB._parts[1].counter.id, 'contribution-section-1-title-count');
assert.equal(sectionB._parts[1].error.id, 'contribution-section-1-title-error');
assert.equal(sectionB._parts[1].label.htmlFor, 'contribution-section-1-title');

const blankCreate = { dataset: { kind: 'spot' }, values: { city_id: '', duration_minutes: '' }, querySelectorAll: () => [] };
const canonicalBlank = api.contributionCanonical(blankCreate);
assert.equal(Object.hasOwn(canonicalBlank, 'city_id'), false, 'clearing city omits it from CREATE payload');
assert.equal(canonicalBlank.duration_minutes, 0, 'blank non-food duration serializes as zero, not the server default');
const savedBlank = api.contributionPayloadForSave('spot', canonicalBlank);
assert.equal(Object.hasOwn(savedBlank, 'city_id'), false);
assert.equal(savedBlank.duration_minutes, 0);
assert.equal(JSON.parse(JSON.stringify(savedBlank)).duration_minutes, 0);

const incompleteDraft = { ...api.contributionDefault('spot'), source_url: 'https://', duration_minutes: 0 }; delete incompleteDraft.city_id;
assert.equal(api.contributionDraftErrors('spot', incompleteDraft).length, 0, 'drafts may retain incomplete links and required fields');
const invalidPreview = api.contributionValidationErrors('spot', incompleteDraft, null);
assert.ok(invalidPreview.some(error => error.id === 'contribution-city-id'));
assert.ok(invalidPreview.some(error => error.id === 'contribution-duration_minutes'));
assert.ok(invalidPreview.some(error => error.id === 'contribution-source_url'));
const completeSpot = { city_id: 7, title: '景点', intro: '介绍', image: '/uploads/a.jpg', description: '正文', theme: '山水风景', duration_minutes: 15, source_url: '' };
assert.equal(api.contributionValidationErrors('spot', completeSpot, null).length, 0, '15 minutes is the accepted lower bound');
assert.ok(api.contributionValidationErrors('spot', { ...completeSpot, duration_minutes: 20161 }, null).some(error => error.id === 'contribution-duration_minutes'));
assert.ok(api.contributionValidationErrors('spot', { ...completeSpot, duration_minutes: 15.5 }, null).some(error => error.id === 'contribution-duration_minutes'));
const guide = { city_id:7,title:'攻略',subtitle:'',intro:'简介',image:'/uploads/g.jpg',source_url:'',theme:'人文历史',duration_minutes:15,sections:[{title:'一段',body:'内容'}],spot_ids:[3] };
assert.equal(api.contributionValidationErrors('guide', guide, null).length, 0);
assert.ok(api.contributionValidationErrors('guide', { ...guide, sections:[{title:'',body:''}], spot_ids:[] }, null).length >= 3);
const invalidSource = api.contributionValidationErrors('food', { city_id:7,title:'美食',intro:'介绍',category:'小吃',description:'内容',source_url:'http://example.com' }, null);
assert.ok(invalidSource.some(error => error.id === 'contribution-source_url'));

const readOnlyDetail = { kind:'spot', operation:'CREATE', status:'PENDING', payload:{ title:'未完成' }, reviews:[] };
const readOnlyHtml = api.contributionFormHtml(readOnlyDetail, { spots:[] }, true);
assert.match(readOnlyHtml, /aria-readonly="true"/);
assert.match(readOnlyHtml, /data-contribution-error-summary[^>]*hidden/);
assert.doesNotMatch(readOnlyHtml, /aria-invalid="true"/);

let scrolls = 0, focuses = 0;
const field = { scrollIntoView: () => scrolls++, focus: options => { assert.equal(options.preventScroll, true); focuses++; } };
const fakeForm = { querySelector: () => field };
api.contributionFocusField(fakeForm, 'contribution-title');
assert.equal(scrolls, 1);
assert.equal(focuses, 1);
const incompletePreview = api.contributionPreviewHtml({ can_submit:false, validation_message:'建议时长请填写 15～20160 分钟' }, []);
assert.match(incompletePreview, /data-contribution-preview-fix="contribution-duration_minutes"/);
assert.doesNotMatch(incompletePreview, /data-contribution-preview-confirm/);
assert.match(incompletePreview, /data-contribution-preview-back/);

console.log('Contribution form checks passed.');

