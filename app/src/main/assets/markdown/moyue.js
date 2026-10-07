/* Thin integration layer. Vditor/Lute distributions remain unmodified. */
(() => {
  'use strict';
  const reading=document.getElementById('reading'),editing=document.getElementById('editing');
  function motion(element) {
    if(document.documentElement.classList.contains('moyue-reduce-motion') || matchMedia('(prefers-reduced-motion: reduce)').matches)return;
    element.getAnimations().forEach(a=>a.cancel());
    element.animate([{opacity:.65},{opacity:1}],{duration:200,easing:'cubic-bezier(.4,0,.2,1)'});
  }
  const cdn='/assets/markdown/vendor/vditor';
  let searchOpen=false;
  let editor=null,current='',persisted='',revision=0,editingMode=false,initializing=false,composing=false,session='',ready=false,lastAck=0,lastPosition=null,renderSequence=0,editorTheme='classic';
  const markdown={sanitize:true,autoSpace:false,fixTermTypo:false,codeBlockPreview:false,mathBlockPreview:true};
  const math={engine:'KaTeX',inlineDigit:false,macros:{}};
  // Public Lute configuration: generated heading IDs become very slow for thousands of repeated
  // headings. Reading still uses Vditor's bundled parser, with inexpensive DOM anchors below.
  const readerLute=Lute.New();readerLute.SetHeadingID(false);readerLute.SetSanitize(true);
  readerLute.SetAutoSpace(false);readerLute.SetFixTermTypo(false);readerLute.SetVditorCodeBlockPreview(false);
  readerLute.SetVditorMathBlockPreview(false);readerLute.SetFootnotes(true);readerLute.SetGFMAutoLink(true);
  function send(type,data={}) {const message=JSON.stringify({type,session,...data});if(window.Moyue)window.Moyue.post(message);else window.testMessages?.push(JSON.parse(message));}
  window.addEventListener('error',e=>send('error',{message:e.message}));
  window.addEventListener('unhandledrejection',e=>send('error',{message:String(e.reason)}));
  function heads() {return [...reading.querySelectorAll('h1,h2,h3,h4,h5,h6')];}
  function position() {
    const total=Math.max(0,document.documentElement.scrollHeight-innerHeight);
    const headings=heads();let index=0;
    headings.forEach((h,i)=>{if(h.getBoundingClientRect().top<=80)index=i;});
    const h=headings[index];return {ratio:total?scrollY/total:0,heading:h?.textContent||'',headingIndex:index,offset:h?Math.max(0,-h.getBoundingClientRect().top):scrollY};
  }
  function restore(p) {
    if(!p)return;
    const headings=heads(),candidate=headings[p.headingIndex];
    const h=candidate?.textContent===p.heading?candidate:headings.find(x=>x.textContent===p.heading);
    const top=h?h.getBoundingClientRect().top+scrollY+Math.max(0,p.offset||0):(p.ratio||0)*Math.max(0,document.documentElement.scrollHeight-innerHeight);
    scrollTo(0,top);
  }
  async function render(text,p) {
    const seq=++renderSequence;
    const html=readerLute.Md2HTML(MoyueMath.normalize(text).text);
    if(seq!==renderSequence)return;
    reading.innerHTML=html||'<p style="opacity:.55">这是一篇空白文档，点击编辑开始书写。</p>';
    const ids=new Map();heads().forEach(h=>{const base=h.textContent.trim().replace(/\s+/g,'-').toLowerCase()||'heading';const n=ids.get(base)||0;ids.set(base,n+1);if(!h.id)h.id=n?base+'-'+n:base;});
    reading.querySelectorAll('input').forEach(x=>x.disabled=true);
    reading.querySelectorAll('table').forEach(table=>{const wrap=document.createElement('div');wrap.className='table-scroll';table.replaceWith(wrap);wrap.append(table);});
    reading.querySelectorAll('pre').forEach(pre=>{
      const code=pre.querySelector('code');if(!code)return;
      const copy=document.createElement('button');copy.className='copy-code';copy.textContent='复制';
      copy.onclick=()=>send('copy',{text:code.textContent});pre.prepend(copy);
    });
    reading.querySelectorAll('img').forEach(img=>{
      let url;try{url=new URL(img.getAttribute('src'),location.href);}catch{url=null;}
      const placeholder=()=>{const note=document.createElement('span');note.className='missing-image';note.textContent=(url?.origin!==location.origin?'网络图片未加载：':'缺少本地图片：')+(img.alt||img.getAttribute('src')||'图片');img.replaceWith(note);};
      if(!url||url.origin!==location.origin){placeholder();return;}
      img.onerror=placeholder;
    });
    Vditor.highlightRender({enable:true,style:editorTheme==='dark'?'github-dark':'github',lineNumber:false},reading,cdn);
    Vditor.mathRender(reading,{cdn,math});
    send('outline',{items:heads().map((h,i)=>({index:i,text:h.textContent,level:Number(h.tagName.slice(1))}))});
    requestAnimationFrame(()=>restore(p));
  }
  function emitChange() {
    if(initializing||!ready||composing||!editor)return;
    const value=editor.getValue();if(value===current)return;
    current=value;revision++;send('changed',{text:current,revision});
  }
  function updateEditorLayout() {
    const source=editingMode&&editor?.getCurrentMode()==='sv';
    if(document.body.classList.contains('moyue-source')!==source)motion(editing);
    document.body.classList.toggle('moyue-source',source);
  }
  // Android WebView can report support for dvh while resolving it to zero. Use its measured
  // viewport and update on keyboard/orientation changes instead of trusting the CSS unit.
  function updateViewport() {
    document.documentElement.style.setProperty('--viewport-height',Math.max(1,Math.round(Math.min(innerHeight,visualViewport?.height||innerHeight)))+'px');
  }
  updateViewport();addEventListener('resize',updateViewport);visualViewport?.addEventListener('resize',updateViewport);
  async function enterEdit(mode='ir') {
    if(editor){
      reading.hidden=true;editing.hidden=false;editingMode=true;motion(editing);
      if(MoyueMath.normalize(current).legacy)mode='sv';
      if(editor.getCurrentMode()!==mode) {
        // Reuse the upstream mode switch: Vditor exposes mode buttons rather than a setMode API.
        const button=document.querySelector(`#editor button[data-mode="${mode}"]`);
        if(button)button.click();
      }
      if(mode==='sv')editor.setPreviewMode('both');
      updateEditorLayout();editor.focus();send('mode',{editing:true});return;
    }
    initializing=true;reading.hidden=true;editing.hidden=false;editingMode=true;motion(editing);
    // Lute's rich modes normalize escaped TeX delimiters. Keep these documents in
    // the public source mode and transform only its preview, preserving exact input.
    const sourceMath=MoyueMath.normalize(current).legacy;
    if(sourceMath)mode='sv';
    const more=['list','ordered-list','italic','link','quote','code','table','line'];
    if(!sourceMath)more.push('edit-mode');
    editor=new Vditor('editor',{
      cdn,mode,cache:{enable:false},value:current,lang:'zh_CN',theme:editorTheme,placeholder:'写下标题、正文或待办事项…',height:'auto',minHeight:300,
      toolbar:['undo','redo','|','headings','bold','check',{name:'image-local',tip:'插入图片',icon:'<svg><use xlink:href="#vditor-icon-upload"></use></svg>',click:()=>send('image')},{name:'more',toolbar:more}],
      toolbarConfig:{pin:true},hint:{emoji:{},parse:false},counter:{enable:false},outline:{enable:false},link:{isOpen:false},image:{isPreview:false},
      preview:{actions:[],markdown,math,
        transform:html=>sourceMath?readerLute.Md2HTML(MoyueMath.normalize(editor?.getValue()??current).text):html,
        hljs:{enable:true,style:editorTheme==='dark'?'github-dark':'github',lineNumber:false},theme:{current:editorTheme==='dark'?'dark':'light',path:cdn+'/dist/css/content-theme'},render:{media:{enable:false}}},
      input:()=>emitChange(),after:()=>{
        initializing=false;ready=true;
        // Snapshot normalization without dirtying the original. A change is only emitted after actual user input.
        current=editor.getValue();
        const area=document.getElementById('editor');
        area.addEventListener('compositionstart',()=>composing=true,true);
        area.addEventListener('compositionend',()=>{composing=false;setTimeout(emitChange,0);},true);
        area.addEventListener('input',()=>{if(!composing)queueMicrotask(emitChange);},true);
        // The upstream source textarea synchronizes its scroll to the preview and has no public
        // switch for that handler. Stop only that source scroll at the integration boundary.
        // Both panes still scroll normally; the upstream distribution is kept byte-identical.
        area.addEventListener('scroll',e=>{if(e.target.matches?.('.vditor-sv'))e.stopPropagation();},true);
        new MutationObserver(updateEditorLayout).observe(area,{attributes:true,subtree:true,attributeFilter:['style']});
        updateEditorLayout();
        editor.focus();send('mode',{editing:true});
      }
    });
  }
  window.MoyuePage={
    searchUI(open){searchOpen=!!open;},
    async open(data){session=data.session;current=data.text;persisted=data.text;lastPosition=data.position;ready=true;await render(current,lastPosition);send('opened');if(data.edit)enterEdit();},
    async edit(mode){lastPosition=position();await enterEdit(mode||'ir');},
    save(action=''){if(composing){send('busy',{message:'请先完成当前输入'});return;}emitChange();send('save',{text:revision?current:persisted,revision,action});},
    async saved(data){
      if(data.revision<lastAck)return;lastAck=data.revision;persisted=data.text;
      if(data.action==='read') {reading.hidden=false;editing.hidden=true;editingMode=false;updateEditorLayout();editor?.blur();await render(persisted,lastPosition);motion(reading);send('mode',{editing:false});}
    },
    insert(path){editor?.insertValue('![]('+path+')');emitChange();},
    jump(index){const h=heads()[index];h?.scrollIntoView({block:'start'});},
    record(){if(!editingMode)send('position',position());},
    action(action){send('readAction',{action,...position()});},
    theme(t){
      document.documentElement.classList.toggle('moyue-reduce-motion',!!t.reduceMotion);
      Object.entries(t).filter(([key])=>key!=='dark').forEach(([key,value])=>document.documentElement.style.setProperty('--'+key,String(value)));
      if(typeof t.dark==='boolean') {
        editorTheme=t.dark?'dark':'classic';
        editor?.setTheme(editorTheme,t.dark?'dark':'light',t.dark?'github-dark':'github',cdn+'/dist/css/content-theme');
        Vditor.highlightRender({enable:true,style:t.dark?'github-dark':'github',lineNumber:false},reading,cdn);
      }
    },
    recover(text){current=text;persisted=text;revision++;send('changed',{text,revision});render(text,lastPosition);},
    source(){if(!editingMode)lastPosition=position();return enterEdit('sv');},
    // Called only by device/debug instrumentation, not exposed as a UI command.
    snapshot(){return {editing:editingMode,text:revision?current:persisted,revision,headings:heads().map(x=>x.textContent)};}
  };
  // Dismiss reading search on the first tap without also following/copying the tapped content.
  reading.addEventListener('click',e=>{
    if(searchOpen && !editingMode && !window.getSelection()?.toString()) {
      searchOpen=false;e.preventDefault();e.stopImmediatePropagation();send('dismissSearch');
    }
  },true);
  reading.addEventListener('click',e=>{
    const a=e.target.closest('a');if(!a)return;e.preventDefault();
    const href=a.getAttribute('href')||'';
    if(href.startsWith('#')){const target=document.getElementById(decodeURIComponent(href.slice(1)));target?.scrollIntoView();}
    else if(/^https?:\/\//i.test(href))send('link',{url:href});
  });
  let scrollTimer;addEventListener('scroll',()=>{clearTimeout(scrollTimer);scrollTimer=setTimeout(()=>{if(!editingMode)send('position',position());},500);},{passive:true});
  setInterval(()=>{if(editingMode&&!composing&&revision>lastAck)send('checkpoint',{text:current,revision});},5000);
  send('ready');
})();
