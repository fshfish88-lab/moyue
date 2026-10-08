import {getDocument, GlobalWorkerOptions} from '../pdfjs/build/pdf.mjs';
GlobalWorkerOptions.workerSrc = '../pdfjs/build/pdf.worker.mjs';
const post = data => MoyueIndex.postMessage(JSON.stringify(data));
let pdf, page = Math.max(0,parseInt(location.hash.slice(1),10)||0), busy = false;
const task = getDocument({url:'/pdf-source/document.pdf', disableRange:true, useWorkerFetch:false, isEvalSupported:false,
  cMapUrl:'../pdfjs/web/cmaps/', cMapPacked:true, standardFontDataUrl:'../pdfjs/web/standard_fonts/', wasmUrl:'../pdfjs/web/wasm/', enableXfa:false});
window.nextIndexPage = async () => {
  if (busy) return;
  busy = true;
  try {
    if (page >= pdf.numPages) { await task.destroy(); post({type:'done'}); return; }
    const item = await pdf.getPage(page + 1), content = await item.getTextContent();
    const text = content.items.map(x => (x.str || '') + (x.hasEOL ? '\n' : '')).join('');
    item.cleanup(); busy = false; post({type:'page', page:page++, text});
  } catch (e) { post({type:'error', message:String(e.message || e).slice(0,160)}); }
  finally { busy = false; }
};
try { pdf = await task.promise; await window.nextIndexPage(); }
catch (e) { post({type:'error', message:e.name === 'PasswordException' ? '加密 PDF 请先打开解锁' : String(e.message || e).slice(0,160)}); }
