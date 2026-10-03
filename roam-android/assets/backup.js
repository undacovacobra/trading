// "Your data" on the My taste page: save everything Roam remembers to a file, and restore it later
// (on a new phone, after reinstalling, or if something goes wrong).
(function(){
 const PREFIX=/^roam-/,MAX_BYTES=20e6;
 function collect(){const data={};for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);if(PREFIX.test(k))data[k]=localStorage.getItem(k);}return {app:'roam',format:1,exportedAt:new Date().toISOString(),data};}
 function exportData(){
  try{persist();}catch{}
  const text=JSON.stringify(collect()),name=`roam-backup-${today()}.json`;
  if(window.RoamAndroid?.saveFile){RoamAndroid.saveFile(name,text);return;}
  const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([text],{type:'application/json'}));a.download=name;document.body.appendChild(a);a.click();
  setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove();},1000);toast('Backup downloaded. Keep it somewhere safe.');
 }
 let pending=null;
 async function readBackup(file){
  if(!file)return;
  if(file.size>MAX_BYTES){toast('That file is too large to be a Roam backup.');return;}
  let parsed;try{parsed=JSON.parse(await file.text());}catch{toast('That file isn’t a Roam backup.');return;}
  const entries=parsed?.app==='roam'&&parsed.data&&typeof parsed.data==='object'?Object.entries(parsed.data).filter(([k,v])=>PREFIX.test(k)&&typeof v==='string'):[];
  if(!entries.length){toast('That file isn’t a Roam backup.');return;}
  pending=entries;
  const when=parsed.exportedAt&&!isNaN(new Date(parsed.exportedAt))?new Date(parsed.exportedAt).toLocaleString():'an unknown date';
  openDialog(`<div class="dialog-inner"><div class="detail-kind">RESTORE A BACKUP</div><h2>Bring back this copy?</h2><p>This backup is from ${escapeHTML(when)}. Restoring replaces your current tastes, saved places, trips and suggestions with the ones in the file.</p><button class="dark-button full-button" data-backup="confirm">Restore this backup ${icon('check')}</button><button class="outline-button full-button" data-backup="cancel">Keep what I have</button></div>`);
 }
 function restore(){
  if(!pending)return;
  const before={};for(const k of Object.keys(localStorage))if(PREFIX.test(k))before[k]=localStorage.getItem(k);
  try{for(const k of Object.keys(before))localStorage.removeItem(k);for(const [k,v] of pending)localStorage.setItem(k,v);}
  catch{for(const k of Object.keys(localStorage))if(PREFIX.test(k))localStorage.removeItem(k);for(const [k,v] of Object.entries(before))try{localStorage.setItem(k,v);}catch{}
   pending=null;toast('Restoring failed because the phone is out of space. Nothing was changed.');return;}
  pending=null;location.reload();
 }
 const beforeProfile=renderCompanionProfile;renderCompanionProfile=()=>{beforeProfile();
  $('#profile-panel').insertAdjacentHTML('beforeend',`<section class="planning-settings backup-settings"><div class="detail-kind">YOUR DATA</div><h2>Keep a copy</h2><p>Everything Roam knows about you lives on this phone. Save a backup file before switching phones or reinstalling, then restore it here.</p><div class="backup-actions"><button class="dark-button" data-backup="export">Save a backup ${icon('arrow-down')}</button><button class="outline-button" data-backup="import">Restore from a file ${icon('arrow-up-right')}</button></div><input id="backup-file" type="file" accept="application/json,.json" hidden><p class="fine-print">Your phone also backs Roam up with your Google account when Android backup is on.</p></section>`);
  $('#backup-file').addEventListener('change',e=>{readBackup(e.target.files[0]);e.target.value='';});
 };
 document.addEventListener('click',e=>{const b=e.target.closest('[data-backup]');if(!b)return;e.preventDefault();
  const a=b.dataset.backup;if(a==='export')exportData();else if(a==='import')$('#backup-file')?.click();else if(a==='confirm')restore();else if(a==='cancel'){pending=null;$('#dialog').close();}});
})();
