const lute=Lute.New();lute.SetAutoSpace(false);lute.SetFixTermTypo(false);lute.SetFootnotes(true);lute.SetGFMAutoLink(true);
window.indexMarkdown=text=>{
  const root=document.createElement('div');root.innerHTML=lute.Md2HTML(MoyueMath.normalize(text).text);
  root.querySelectorAll('script,style').forEach(n=>n.remove());
  MoyueMdIndex.postMessage(JSON.stringify({type:'text',text:root.textContent}));
};
MoyueMdIndex.postMessage(JSON.stringify({type:'ready'}));
