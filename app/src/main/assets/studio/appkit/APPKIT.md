# appkit: real phone apps, few lines
Put `appkit.css` + `appkit.js` next to your page; any style skins them, light or dark. Offline. Example app: `demo.html`.

## Skeleton (start here)
```html
<!doctype html><html lang="en" data-kind="app" data-app="jobs">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Jobs | {{business}}</title>
<link rel="stylesheet" href="fonts.css"><link rel="stylesheet" href="theme.css">
<link rel="stylesheet" href="appkit.css"></head>
<body><script src="appkit.js"></script><script>
const db = AK.store('jobs', {jobs: []});
const app = AK.app({title: 'Jobs', icon: 'wrench', fab: {icon: 'plus', label: 'New job', act: 'new'},
 tabs: [
  {id: 'list', label: 'Jobs', icon: 'clipboard', render: page => {
   const jobs = db.get('jobs');
   page.innerHTML = AK.html`<header class="page-head"><div><h1>Jobs</h1><p class="sub">${jobs.length} open</p></div></header>
   ${jobs.length ? AK.html`<ul class="list">${jobs.map(j => AK.html`<li><button class="item" data-act="del" data-id="${j.id}">
    <span class="lead">${AK.icon('car')}</span><span class="body"><span class="title">${j.title}</span><span class="sub">${AK.fmt.money(j.price)}</span></span></button></li>`)}</ul>`
   : AK.html`<div class="card">${AK.empty({title: 'No jobs yet', text: 'Tap New job to add one.', action: {label: 'New job', act: 'new'}})}</div>`}`;
  }},
  {id: 'more', label: 'More', icon: 'settings', fab: false, render: () => AK.html`<header class="page-head"><div><h1>More</h1></div></header>`}
 ]});
db.subscribe(() => app.refresh());
AK.act({
 new: async () => { const t = await AK.prompt('What needs doing?'); if (t) db.add('jobs', {title: t, price: 0}); },
 del: async el => { if (await AK.confirm('Delete this job?', {ok: 'Delete', danger: true})) { db.remove('jobs', el.dataset.id); AK.toast('Job deleted', {undo: () => db.undo()}); } }
});
</script></body></html>
```
`render(page, ctx)` fills `page` (or returns html); one `h1` per page. `app.go('/list/ID')` = detail route (`ctx.param`, back button). Clicks: `data-act="name"` + `AK.act` once (no leaks); bind other events to elements you just rendered.

## Classes
Shell: `.page-head` (`.eyebrow` `h1` `.sub` `.actions`), `.section` + `.sec-head`, `.card` (`.card-head` `.card-foot`), `.stats` > `.stat` (`.label` `.value` `.meta` `.delta.up/.down`, `.stat.accent` = hero number), `.note` (`.ok/.warn/.bad`), `.toolbar`, `.cols`, `.stack`, `.row`, `.btns`.
List: `.list` > `li` > `.item` (a/button) = `.lead` or `AK.avatar()` + `.body` (`.title` `.sub`) + `.trail` (`.value` `.sub`).
Buttons: `.btn` + `.primary` `.soft` `.ghost` `.danger`, `.sm` `.lg` `.icon` `.block`; loading `aria-busy="true"`.
Forms (controls need no class): `.form` > `.field` > `label` + control + `.help`. Money `.affix` > `span`$ + `input data-money`. Search `.search` > `input type=search`. `label.check` > checkbox/radio; `input.switch`; `.seg` > `label` > radio + `span`; `.stepper` > `input type=number`.
Bits: `.chips` > `button.chip aria-pressed` (`data-single` = one at a time), `.pill.ok/.warn/.bad/.info`, `.badge`, `.avatar`, `.progress style="--value:60"`, `.ring style="--value:60"`, `.skel`, `.tabs` > `.tablist` > buttons + `.panel`s, `.accs` > `details.acc` > `summary`, `.doc` (printable: `.doc-head` `.doc-meta` `table` `.doc-total` `.doc-sign` `.doc-foot`), `.hide-phone` `.hide-desk` `.no-print`.

## API (window.AK)
| Call | Does |
|---|---|
| `AK.app({title,icon,tabs,actions,fab})` | shell, tabs (sidebar >= 900px), routes, back: `{go,back,refresh,badge(tab,n),route}` |
| `AK.store(name, defaults)` | saved state: `get(path)` `set(obj or path,v)` `update(fn)` `subscribe(fn)` `undo()` `reset()` `export()` `import()` `.persistent` |
| `s.add(list,item)` `s.update(list,id,changes)` `s.remove(list,id)` `s.find(list,id)` | array helpers (ids made for you) |
| `AK.html\`...\`` `AK.raw(s)` | escaping HTML template / trusted HTML |
| `AK.el(tag,attrs,kids)` `AK.render(el,html)` `AK.on(root,evt,sel,fn)` `AK.act(name,fn)` | DOM helpers, delegation, actions |
| `AK.list(ul,items,row,{empty})` | keyed list: only changed rows redraw |
| `AK.toast(msg,{type,undo})` | bottom message; type ok/warn/bad/info |
| `AK.modal(o)` `AK.sheet(o)` | `{title,body,actions:[{label,primary,danger,onClick}],onOpen}`; onClick false = stay open |
| `AK.confirm(title,{text,ok,danger})` `AK.prompt(title,{value,label,type})` | promise: true/false, text/null |
| `AK.validate(form,rules)` `AK.values(form)` `AK.fill(form,data)` | `{ok,values,errors}`; rules `{name:{required,min,max,email,phone,pattern,test(v)}}` |
| `AK.table(el,{columns,rows,sort,search:true,onRow})` | sort, search, cards on phones; column `{key,label,num,format:'money'/'date'/fn,html}` |
| `AK.csv.export(rows,file)` `AK.csv.parse(text)` `AK.csv.import()` | CSV out / in (promise of rows) |
| `AK.fmt.money(n)` `.number` `.percent` `.date(d,'long')` `.day` `.time` `.ago` | `AK.parseMoney('$1,250')` = 1250, `AK.today(+7)` = date string |
| `AK.chart.bar/line/area(el,{labels,values or series,format})` `.donut(el,{labels,values})` `.spark(el,values)` | themed SVG charts, tap/arrows show values |
| `AK.photo()` | promise: resized JPEG data URL (camera/gallery) |
| `AK.signature(canvas)` | `{clear(),isEmpty(),toDataURL({color:'#000'}),toJSON(),load()}` |
| `AK.drag(ul,{onSort(ids)})` `AK.kanban(el,{columns,cards,card,onMove,onOpen})` | reorder (`.handle` + `data-id`); board (hold card to drag, Shift+arrows) |
| `AK.print(title)` `AK.share({title,text})` `AK.copy(t)` `AK.download(f,data)` | output |
| `AK.sms(n,text)` `AK.mail(to,subj,body)` `AK.tel(n)` | links |
| `AK.empty({art,title,text,action})` `AK.skeleton(n)` `AK.icon(n)` `AK.avatar(name)` | art: list search inbox chart photo people; 70 icons: `AK.icons` |
| `AK.pwa({name})` | home-screen name/icon once published (no offline cache) |

## 10 rules for a great app
1. Big numbers first: open with `.stats` (one `.stat.accent`).
2. One primary action per screen (the FAB or one `.btn.primary`).
3. Every list has an empty state saying what to do next.
4. Confirm destructive actions (`AK.confirm` `danger`), then offer Undo in a toast.
5. Save every change (`AK.store`); never lose typing.
6. Show progress: `aria-busy` buttons, `AK.skeleton` while loading.
7. Forms: visible labels, inline errors (`AK.validate`), right keyboard (`type=tel`, `inputmode=decimal`).
8. Phone first: big targets, actions at the bottom, sheets over new pages.
9. Theme colours only; show status with `.pill` words, not colour alone.
10. No invented customers or numbers; label sample data as sample.

## Good to know
- In Alpha's preview, photo and file pickers, downloads (saved through the phone's "save as" screen), print (the phone's print screen, PDF too) and share all work, each with the phone's own screen. sms/tel/mail links work. Offer `AK.copy` too.
- Storage is per device (~5 MB): few photos. Private mode keeps data until the page closes.
- Lint warn "more than one accent colour family" (status pills) is expected.
- Call `AK.app({...})` LAST in your script, after every helper it uses (it draws the first screen at once). Pages reuse one `.page` element: use `data-act` + `AK.act`, or bind listeners to elements you just rendered, never to `.page` itself.
