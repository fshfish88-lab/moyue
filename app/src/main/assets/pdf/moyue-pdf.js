/* 墨阅 adapter: upstream PDF.js parser, viewer, thumbnails and search stay unchanged. */
(() => {
  'use strict';
  let app, config, restoring = true, lastLocation, positionTimer;
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
