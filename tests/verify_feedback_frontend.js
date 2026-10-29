"use strict";
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const file = path.join(root, "src", "main", "resources", "static", "js", "feedback.js");
const source = fs.readFileSync(file, "utf8");
const checks = [];
function check(name, ok) {
  if (!ok) throw new Error("反馈前端行为检查失败: " + name);
  checks.push(name);
}
function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, ch => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[ch]));
}

// Evaluate the shipped functions with only the escaping helper they use.
const sandbox = { esc, location: { pathname: "/trip/123" }, document: { addEventListener() {} } };
vm.createContext(sandbox);
vm.runInContext(source, sandbox, { filename: file, timeout: 1000 });
if (typeof sandbox.feedbackSource !== "function") throw new Error("feedback.js did not expose feedbackSource");

const payload = '<img src=x onerror="globalThis.pwned=1"><script>alert(1)</script>';
const html = sandbox.feedbackSource({
  page_path: "/guide/12#comment-34",
  page_title: payload,
  target_excerpt: payload,
  source_available: true,
});
check("malicious snapshot title and excerpt are rendered as text", !html.includes("<img") && !html.includes("<script>") && html.includes("&lt;img") && html.includes("&lt;script&gt;"));
check("canonical guide comment path remains a local link", /href="\/guide\/12#comment-34"/.test(html));

const unavailable = sandbox.feedbackSource({page_path:"/spot/4",page_title:"Old spot",target_excerpt:"snapshot",source_available:false});
check("unavailable snapshot never renders a source link", !/<a\b/i.test(unavailable) && unavailable.includes("原内容当前不可用") && unavailable.includes("snapshot"));
check("empty source path renders no link or source container", sandbox.feedbackSource({page_path:"",page_title:"",target_excerpt:"",source_available:false}) === "");

for (const unsafe of ["javascript:alert(1)", "//evil.invalid/path", "/spot/4?next=//evil.invalid", "/spot/%2f%2fevil", "/spot/4\\..\\admin"]) {
  const result = sandbox.feedbackSource({page_path:unsafe,page_title:"x",target_excerpt:"",source_available:true});
  check("untrusted path does not create an unsafe href: " + unsafe, !/<a\b/i.test(result));
}

if (typeof sandbox.feedbackPathAllowed !== "function" || typeof sandbox.feedbackCanonicalPath !== "function" || typeof sandbox.feedbackOpenButton !== "function") {
  throw new Error("feedback.js is missing canonical path helpers");
}
for (const allowed of ["/", "/destinations", "/guides", "/food", "/community", "/routes", "/search", "/recommend", "/trips", "/me", "/spot/12", "/guide/12", "/guide/12#comment-34", "/destination/nan-yang", "/food/9", "/community/post/8", "/community/post/8#comment-34", "/routes/7"]) {
  check("canonical local feedback path is allowed: " + allowed, sandbox.feedbackPathAllowed(allowed) === true);
}
for (const rejected of ["", "javascript:alert(1)", "//evil.invalid", "/spot/12?x=1", "/spot/%31", "/guide/12#comment-0", "/guide/12#comment-34?x=1", "/trip/123", "/admin", "/feedback", "/destination/BadSlug"]) {
  check("noncanonical or private feedback path is rejected: " + rejected, sandbox.feedbackPathAllowed(rejected) === false);
}
sandbox.location.pathname = "/trip/123";
check("private trip route collapses to non-identifying trips page", sandbox.feedbackCanonicalPath() === "/trips");
sandbox.location.pathname = "/me";
check("personal center canonical path remains /me", sandbox.feedbackCanonicalPath() === "/me");
sandbox.location.pathname = "/admin";
check("admin path is never used as feedback context", sandbox.feedbackCanonicalPath() === "");
sandbox.location.pathname = "/feedback";
check("feedback page does not become its own source", sandbox.feedbackCanonicalPath() === "");
const reportButton = sandbox.feedbackOpenButton("/community/post/8#comment-34", "举报评论", true);
check("comment report button carries only the canonical safe path", reportButton.includes('data-path="/community/post/8#comment-34"') && reportButton.includes('data-report="1"') && !reportButton.includes("javascript:"));

// Page rendering continues to HTML-escape user supplied source text and uses the server context resolver.
const page = source.slice(source.indexOf("async function feedbackPage("), source.indexOf("\nasync function adminFeedbackPage("));
check("feedback page resolves submitted context through the API", /api\/feedback\/context\?path='\+encodeURIComponent\(source\)/.test(page));
check("feedback page escapes its preview title and excerpt", /esc\(context\.page_title\)/.test(page) && /esc\(context\.target_excerpt\)/.test(page));
check("feedback page escapes typed source path and context errors", /value=\"'\+esc\(source\)/.test(page) && /esc\(contextError/.test(page));

const admin = source.slice(source.indexOf("async function adminFeedbackPage("), source.indexOf("\nasync function feedbackAction("));
check("admin lists use the snapshot renderer", /feedbackSource\(row\)/.test(admin));
check("admin search text is URL encoded", /encodeURIComponent\(q\)/.test(admin));
console.log(JSON.stringify({ file, checks_passed: checks.length, checks }, null, 2));
