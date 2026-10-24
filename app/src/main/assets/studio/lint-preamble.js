/* lint-preamble.js | inject at DOCUMENT START (before page scripts); generated from lint-rules.md */
window.__lintErrs = [];
addEventListener('error', function (e) { var t = e.target; __lintErrs.push(t && t !== window ? 'load failed: ' + (t.src || t.href || t.tagName) : 'js error: ' + (e.message || 'unknown')); }, true);
addEventListener('unhandledrejection', function (e) { __lintErrs.push('unhandled rejection: ' + e.reason); });
var _ce = console.error; console.error = function () { __lintErrs.push('console.error: ' + [].join.call(arguments, ' ')); _ce.apply(console, arguments); };
