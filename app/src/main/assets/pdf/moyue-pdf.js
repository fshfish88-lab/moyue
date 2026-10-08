/* 墨阅 adapter: upstream PDF.js parser, viewer, thumbnails and search stay unchanged. */
(() => {
  'use strict';
  let app, config, restoring = true, lastLocation, positionTimer;
  let annotations=[], pendingAnnotation=null, annotationBar, indexedPages=new Set();
  function drawAnnotations() {
    document.querySelectorAll('.moyue-highlight-layer').forEach(n=>n.remove());
    const colors={yellow:'#efc75266',green:'#6fcf9766',blue:'#6caff066',pink:'#f493b266'};
    const pages=new Map();
    for(const item of annotations){
      const a=item.anchor;if(a.kind!=='PDF'||!a.pageRects?.length)continue;
      const page=app.pdfViewer.getPageView(a.pageIndex),node=page?.div;if(!node||!page.viewport)continue;
      for(const rect of a.pageRects){
        const r=[...page.viewport.convertToViewportPoint(rect[0],rect[1]),...page.viewport.convertToViewportPoint(rect[2],rect[3])];
        const mark={left:Math.min(r[0],r[2])/page.viewport.width,top:Math.min(r[1],r[3])/page.viewport.height,right:Math.max(r[0],r[2])/page.viewport.width,bottom:Math.max(r[1],r[3])/page.viewport.height,color:colors[item.color]||colors.yellow};
        if(mark.right<=mark.left||mark.bottom<=mark.top)continue;
        const regions=pages.get(a.pageIndex)||[];
        pages.set(a.pageIndex,regions.flatMap(old=>{
          const left=Math.max(old.left,mark.left),top=Math.max(old.top,mark.top),right=Math.min(old.right,mark.right),bottom=Math.min(old.bottom,mark.bottom);
          if(left>=right||top>=bottom)return [old];
          return [old.top<top?{...old,bottom:top}:null,old.bottom>bottom?{...old,top:bottom}:null,old.left<left?{...old,top,bottom,right:left}:null,old.right>right?{...old,top,bottom,left:right}:null].filter(Boolean);
        }).concat(mark));
      }
    }
    for(const [index,regions] of pages){
      const layer=document.createElement('div');layer.className='moyue-highlight-layer';
      Object.assign(layer.style,{position:'absolute',inset:'0',pointerEvents:'none',zIndex:'4'});
      for(const r of regions){const mark=document.createElement('div');
        // Percentages follow PDF.js's immediate CSS zoom while the canvas rerender is delayed.
        Object.assign(mark.style,{position:'absolute',left:r.left*100+'%',top:r.top*100+'%',width:(r.right-r.left)*100+'%',height:(r.bottom-r.top)*100+'%',background:r.color});layer.append(mark);
      }app.pdfViewer.getPageView(index).div.append(layer);
    }
  }
  function selectionChanged() {
    const s=window.getSelection();if(!annotationBar||!s?.rangeCount||s.isCollapsed){if(annotationBar)annotationBar.style.display='none';return;}
    const r=s.getRangeAt(0),first=(r.startContainer.nodeType===1?r.startContainer:r.startContainer.parentElement)?.closest('.page'),last=(r.endContainer.nodeType===1?r.endContainer:r.endContainer.parentElement)?.closest('.page');
    if(!first||first!==last||!s.toString().trim()||s.toString().length>32768){annotationBar.style.display='none';return;}
    const index=Number(first.dataset.pageNumber)-1,p=app.pdfViewer.getPageView(index),bounds=first.getBoundingClientRect();
    const rects=[...r.getClientRects()].filter(x=>x.width>0&&x.height>0).map(x=>{
      const a=p.viewport.convertToPdfPoint(Math.max(0,x.left-bounds.left),Math.max(0,x.top-bounds.top));
      const b=p.viewport.convertToPdfPoint(Math.min(bounds.width,x.right-bounds.left),Math.min(bounds.height,x.bottom-bounds.top));
      return [Math.min(a[0],b[0]),Math.min(a[1],b[1]),Math.max(a[0],b[0]),Math.max(a[1],b[1])];
    }).slice(0,512);
    if(!rects.length)return;
    pendingAnnotation={anchor:{kind:'PDF',pageIndex:index,pageRects:rects,quote:s.toString(),position:position()},text:s.toString()};annotationBar.style.display='flex';
  }
  // AndroidView may initially measure WebView as wrap-content. Keep the fixed viewer shell tied
  // to the actual native viewport; reflowed Markdown does not need this layout adapter.
  const sizeHost = () => { if(innerHeight > 0) document.documentElement.style.height=innerHeight+'px'; };
  addEventListener('resize',sizeHost);
  const post = (type, data = {}) => {
    if(window.MoyuePdfBridge) MoyuePdfBridge.postMessage(JSON.stringify({type, session:config?.session || '', ...data}));
  };
  const position = () => {
    const loc = lastLocation;
    return {pageIndex:Math.max(0,(loc?.pageNumber || app.page || 1)-1), left:loc?.left || 0, top:loc?.top || 0,
      scale:String(app.pdfViewer.currentScaleValue || 'page-width')};
  };
  function motion() {
    if(config?.reduceMotion || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const viewer=document.getElementById('viewer');
    viewer.getAnimations().forEach(a=>a.cancel());
    viewer.animate([{opacity:.65},{opacity:1}],{duration:200,easing:'cubic-bezier(.4,0,.2,1)'});
  }
  const manager = () => app?.viewsManager || app?.pdfSidebar;
  function closeSidebar() {
    const selector=document.getElementById('viewsManagerSelectorButton');
    if(selector?.getAttribute('aria-expanded')==='true')selector.click();
    manager()?.close();
  }
  function report() { if(!restoring && app?.pdfDocument) post('position', position()); }
  window.MoyuePdf = {
    annotations(items){annotations=items;drawAnnotations();},
    jumpAnnotation(a){
      if(a.position?.invalidLegacy){post('annotationMissing');return;}
      if(a.position)this.restore(a.position);else this.page(a.pageIndex+1);
      if(a.quote && !a.pageRects?.length){this.find(a.quote);}
      else if(a.pageRects?.length){const rect=a.pageRects[0];app.pdfViewer.scrollPageIntoView({pageNumber:a.pageIndex+1,destArray:[null,{name:'XYZ'},rect[0],rect[3],null]});}
    },
    async open(value) {
      config = value;
      try {
        restoring = true;
        await app.open({url:'/pdf-source/document.pdf', originalUrl:'document.pdf', disableRange:true, useWorkerFetch:false, isEvalSupported:false});
        await app.pdfViewer.pagesPromise;
        this.mode(value.mode || 'continuous');
        const p = value.position || {};
        const saved = Number.isInteger(p.pageIndex);
        app.pdfViewer.currentScaleValue = p.scale || value.fit || 'page-width';
        app.page = Math.min(app.pagesCount, Math.max(1,(p.pageIndex || 0)+1));
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const scale = parseFloat(p.scale);
        if(saved) app.pdfViewer.scrollPageIntoView({pageNumber:app.page, destArray:[null,{name:'XYZ'},p.left || 0,p.top || 0,Number.isFinite(scale) ? scale : null]});
        else app.pdfViewer.scrollPageIntoView({pageNumber:1});
        restoring = false;
        post('opened',{pages:app.pagesCount}); report();
      } catch(error) { post('error',{message:'PDF 无法打开：'+String(error.message || error).slice(0,200)}); }
    },
    page(n) { if(app.pdfDocument) {app.page = Math.min(app.pagesCount, Math.max(1,Number(n)||1)); motion();} },
    zoom(direction) { if(direction > 0) app.zoomIn(); else app.zoomOut(); motion(); },
    fit(value) { app.pdfViewer.currentScaleValue = value; motion(); },
    mode(value) { app.pdfViewer.scrollMode = value === 'single' ? 3 : 0; if(!restoring)motion(); },
    restore(p) {
      app.pdfViewer.currentScaleValue = p.scale || 'page-width';
      app.pdfViewer.scrollPageIntoView({pageNumber:p.pageIndex+1,destArray:[null,{name:'XYZ'},p.left||0,p.top||0,null]});
    },
    search(query) { app.eventBus.dispatch('find', {source:window, type:'', query:String(query), caseSensitive:false, entireWord:false, highlightAll:true, findPrevious:false, matchDiacritics:false}); },
    nextMatch(previous) { app.eventBus.dispatch('find', {source:window, type:'again', query:config.query||'', caseSensitive:false, entireWord:false, highlightAll:true, findPrevious:!!previous}); },
    find(query) { config.query = query; this.search(query); },
    stopFind() { app?.eventBus?.dispatch('findbarclose',{source:window}); },
    closeSidebar,
    sidebar(view) {
      const manager = app.viewsManager || app.pdfSidebar;
      if(!manager) return;
      if(manager.isOpen && manager.visibleView === view) manager.close();
      else manager.switchView(view,true);
    },
    appearance(value) {
      document.documentElement.style.setProperty('--moyue-background',value.background);
      document.documentElement.classList.toggle('moyue-invert',!!value.invert);
      document.documentElement.style.colorScheme = value.night ? 'dark' : 'light';
    },
    chrome(value) {
      if(config)config.reduceMotion=!!value.reduceMotion;
      document.documentElement.classList.toggle('moyue-reduce-motion',!!value.reduceMotion);
      document.documentElement.style.setProperty('--moyue-chrome-top',Math.max(8,value.top||0)+'px');
      document.documentElement.style.setProperty('--moyue-chrome-bottom',Math.max(8,value.bottom||0)+'px');
    },
    snapshot() { clearTimeout(positionTimer); report(); }
  };
  document.addEventListener('webviewerloaded', () => {
    sizeHost();
    app = window.PDFViewerApplication;
    const options = window.PDFViewerApplicationOptions;
    options.set('defaultUrl',''); options.set('disablePreferences',true);
    options.set('enableScripting',false); options.set('annotationEditorMode',-1);
    options.set('enableXfa',false); options.set('externalLinkTarget',2);
    options.set('enableSplitMerge',false); options.set('enableComment',false);
    options.set('enableHighlightFloatingButton',false); options.set('enableSignatureEditor',false);
    options.set('localeProperties',{lang:'zh-CN'});
    options.set('maxCanvasPixels',8388608);
    app.initializedPromise.then(() => {
      annotationBar=document.createElement('div');
      Object.assign(annotationBar.style,{position:'fixed',bottom:'84px',left:'50%',transform:'translateX(-50%)',zIndex:'100000',display:'none',background:'#fff',color:'#222',borderRadius:'10px',padding:'8px',boxShadow:'0 2px 12px #0003'});
      for(const [type,name] of [['HIGHLIGHT','高亮'],['NOTE','笔记']]){const b=document.createElement('button');b.textContent=name;b.type='button';Object.assign(b.style,{fontSize:'16px',padding:'8px 16px',border:0,background:'transparent',color:'#222'});b.onpointerdown=e=>e.preventDefault();b.onclick=()=>{if(pendingAnnotation)post('annotation',{...pendingAnnotation,annotationType:type});window.getSelection()?.removeAllRanges();annotationBar.style.display='none';};annotationBar.append(b);}
      document.body.append(annotationBar);document.addEventListener('selectionchange',selectionChanged);
      app.eventBus.on('scalechanging',drawAnnotations);
      app.eventBus.on('rotationchanging',drawAnnotations);
      app.eventBus.on('textlayerrendered',({pageNumber})=>{
        drawAnnotations();if(indexedPages.has(pageNumber))return;indexedPages.add(pageNumber);
        app.pdfDocument.getPage(pageNumber).then(p=>p.getTextContent()).then(t=>post('indexedPage',{page:pageNumber-1,text:t.items.map(x=>(x.str||'')+(x.hasEOL?'\n':'')).join('')})).catch(()=>{});
      });
      // In PDF.js 6 the sidebar lives inside the toolbar. Move this existing node outside the
      // hidden upstream toolbar; all official view-manager references and handlers remain intact.
      const sidebar=document.getElementById('viewsManager');
      if(sidebar) {
        const host=document.getElementById('mainContainer');host.append(sidebar);
        const backdrop=document.createElement('button');backdrop.className='moyue-sidebar-backdrop';backdrop.type='button';backdrop.hidden=true;
        backdrop.setAttribute('aria-label','关闭导航面板');backdrop.onclick=closeSidebar;host.insertBefore(backdrop,sidebar);
        const close=document.createElement('button');close.id='moyue-sidebar-close';close.type='button';close.textContent='×';close.setAttribute('aria-label','关闭导航面板');close.onclick=closeSidebar;
        document.getElementById('viewsManagerTitle').append(close);
        app.eventBus.on('sidebarviewchanged',({view})=>{backdrop.hidden=view===0;post('sidebar',{view});});
        // Capture before upstream stops bubbling, but close only after its navigation runs.
        sidebar.addEventListener('click',e=>{
          const target=e.target.closest('.thumbnailImageContainer, #outlinesView a, #outlineView a');
          if(target && !e.target.closest('.treeItemToggler'))setTimeout(closeSidebar,0);
        },true);
        sidebar.addEventListener('keydown',e=>{
          if(e.key==='Enter' && e.target.closest('.thumbnailImageContainer, #outlinesView a'))setTimeout(closeSidebar,0);
        },true);
      }
      const passwordDialog=document.getElementById('passwordDialog');
      new MutationObserver(()=>post('password',{open:passwordDialog.open})).observe(passwordDialog,{attributes:true,attributeFilter:['open']});
      app.eventBus.on('updateviewarea', ({location}) => { lastLocation=location; clearTimeout(positionTimer); positionTimer = setTimeout(report,250); });
      app.eventBus.on('pagechanging', ({pageNumber}) => { if(!restoring) post('page',{page:pageNumber}); });
      app.eventBus.on('updatefindmatchescount', ({matchesCount}) => post('matches', matchesCount));
      app.eventBus.on('updatefindcontrolstate', ({state,matchesCount}) => post('matches',{...matchesCount,state}));
      app.eventBus.on('outlineloaded', ({outlineCount}) => post('outline',{count:outlineCount}));
      app.eventBus.on('pagerendered', ({pageNumber}) => {
        drawAnnotations();
        document.querySelectorAll('.annotationLayer input,.annotationLayer textarea,.annotationLayer select').forEach(el => {el.disabled=true;});
        if(pageNumber===1) {
          const canvas = app.pdfViewer.getPageView(0)?.canvas;
          if(canvas && !window.moyueCoverSent) {
            window.moyueCoverSent=true;
            const thumb=document.createElement('canvas'); thumb.width=360; thumb.height=Math.max(1,Math.min(720,Math.round(360*canvas.height/canvas.width)));
            thumb.getContext('2d').drawImage(canvas,0,0,thumb.width,thumb.height);
            post('cover',{image:thumb.toDataURL('image/png')});
          }
        }
      });
      // Click detection ignores pans, pinches, links and selected text.
      let touch;
      const viewer=document.getElementById('viewerContainer');
      viewer.addEventListener('pointerdown',e=>{touch={x:e.clientX,y:e.clientY,time:Date.now()};},{passive:true});
      viewer.addEventListener('pointerup',e=>{
        if(touch && Date.now()-touch.time<300 && Math.hypot(e.clientX-touch.x,e.clientY-touch.y)<8 && !window.getSelection()?.toString() && !e.target.closest('a,input,button')) {if(manager()?.isOpen)closeSidebar();else post('tap');} touch=null;
      },{passive:true});
      viewer.addEventListener('pointercancel',()=>{touch=null;},{passive:true});
      post('ready');
    }).catch(error=>post('error',{message:String(error)}));
  });
})();
