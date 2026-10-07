import {getDocument, GlobalWorkerOptions} from '../pdfjs/build/pdf.mjs';
GlobalWorkerOptions.workerSrc = '../pdfjs/build/pdf.worker.mjs';
// WebView's intercepted local responses stream correctly, but concurrent range cancellation is
// not reliable. Use the public sequential-stream option rather than patching PDF.js internals.
const task = getDocument({url: '/pdf-source/document.pdf', disableRange:true, useWorkerFetch:false, isEvalSupported:false, cMapUrl: '../pdfjs/web/cmaps/', cMapPacked: true, standardFontDataUrl: '../pdfjs/web/standard_fonts/', wasmUrl: '../pdfjs/web/wasm/', enableXfa: false});
try {
  const pdf = await task.promise;
  const {info} = await pdf.getMetadata();
  const result={ok:true,pages:pdf.numPages,title:info.Title||'',author:info.Author||''};
  await task.destroy();
  MoyueProbe.postMessage(JSON.stringify(result));
} catch(error) {
  console.error('PDF probe error',error.stack);
  if(error.name === 'PasswordException') MoyueProbe.postMessage(JSON.stringify({ok:true, locked:true}));
  else MoyueProbe.postMessage(JSON.stringify({ok:false, error:'PDF 损坏或不受支持：'+String(error.message).slice(0,200)}));
  await task.destroy();
}
