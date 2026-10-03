/* Photo URLs are discovered from the venue page at runtime, never a gallery catalog. */
(function(){
 const cache=new Map(),TTL=4*60*60*1000;
 let pending;
 function safeImage(value,base){try{const u=new URL(value,base);if(u.protocol!=='https:'||u.username||u.password||u.port||/^(localhost|.*\.local|\[|[\d.]+$)/i.test(u.hostname))return null;return u.href;}catch{return null;}}
 function extract(html,sourceURL,venue){
  const doc=new DOMParser().parseFromString(html,'text/html');
  const seen=new Set(),photos=[];
  const reject=/IG[ _-]*POSTS|website[ _-]*banners|tv[ _-]*slides|comedian|logo|favicon|icons?\b|\bui[-_]|bg[-_]|gift[+% _-]*card|poster|flyer|static.social|newsletter|chatgpt|ai[ _-]generated|illustration|graphic|sponsor|menu[_. -]|merch|t[ _-]shirt|screen[+ _-]*shot|\bVail\b|Chatfield|\b1080x1080\b|\bsquare\b|placeholder|youtube|thumbnail|digital.desktop|digital.tablet|digital.mobile|waterbottle|untitled.design/i;
  const tokens=(venue.name+' '+venue.city).toLowerCase().match(/[a-z]{4,}/g)||[];
  const candidates=[...doc.querySelectorAll('meta[property="og:image"],img,[data-background],[data-bg],[data-bg-image],[style*="background-image"]')];
  doc.querySelectorAll('style').forEach(style=>{for(const match of style.textContent.matchAll(/background-image\s*:\s*url\(["']?([^"')]+)["']?\)/gi)){const item=doc.createElement('span');item.setAttribute('data-bg',match[1]);candidates.push(item);}});
  candidates.forEach(img=>{
   if(img.closest('header,footer,nav,.logo,.product-item,.ProductItem,.sqs-product-block,.event-card,.card-event,.blog-item,.summary-item-record-type-text'))return;
   const card=img.closest('.box-card,.event,.post,.tribe-events-widget-events-list__event');
   if(card?.querySelector('a[href*="/event"],a[href*="/blog"],a[href*="/news"]'))return;
   if(/\/(events?|blog|news)\//i.test(img.closest('[data-detail-page]')?.getAttribute('data-detail-page')||''))return;
   if(/\/(events?|blog|news|shop)\//i.test(img.closest('a')?.getAttribute('href')||''))return;
   let value=img.getAttribute('content')||img.getAttribute('data-src')||img.getAttribute('data-image')||img.getAttribute('data-lazy-src')||img.getAttribute('src')||img.getAttribute('data-background')||img.getAttribute('data-bg')||img.getAttribute('data-bg-image')||img.getAttribute('style')?.match(/url\(["']?([^"')]+)["']?\)/)?.[1];
   if(!value||/^data:/i.test(value))value=(img.getAttribute('srcset')||'').split(',').pop()?.trim().split(/\s+/)[0];
   const url=safeImage(value,sourceURL);if(!url||!/\.(jpe?g|png|webp)(?:[/?#]|$)/i.test(url)||/cdninstagram\.com|fbcdn\.net/i.test(new URL(url).hostname))return;
   const caption=img.getAttribute('alt')||img.closest('figure')?.querySelector('figcaption')?.textContent||'';
   let decoded;try{decoded=decodeURIComponent(url);}catch{decoded=url;}
   const description=decoded+' '+caption;
   if(reject.test(description)||venue.photoExclude&&new RegExp(venue.photoExclude,'i').test(description))return;
   if(venue.category==='Outdoors'&&!/trail|mountain|meadow|overlook|flatiron|landscape|amphitheat|alivecoverage|red.?rocks|lost.?gulch/i.test(description))return;
   const dimensions=(img.getAttribute('data-image-dimensions')||'').split('x').map(Number);
   const w=Number(img.getAttribute('width'))||dimensions[0],h=Number(img.getAttribute('height'))||dimensions[1];
   if((w&&w<300)||(h&&h<180))return;
   const key=new URL(url);key.search='';key.hash='';
   // WordPress and Wix repeat the same image at several sizes.
   const identity=key.href.replace(/-\d+x\d+(?=\.)/,'').replace(/(\.(?:jpe?g|png|webp))\/v1\/.*$/i,'$1');
   if(seen.has(identity))return;seen.add(identity);
   const score=(img.tagName==='META'?35:0)+tokens.filter(t=>(decoded+' '+caption).toLowerCase().includes(t)).length*10+(img.closest('[class*="gallery"]')?6:0)+(w>=600?2:0);
   const display=new URL(url);if(display.hostname==='images.squarespace-cdn.com')display.searchParams.set('format','1000w');
   const photoURL=display.href.replace(/(static\.wixstatic\.com\/media\/[^?]+?\.(?:jpg|png|webp))\/v1\/.*$/i,'$1');
   photos.push({url:photoURL,caption:caption.trim().slice(0,250)||'Photo from '+venue.name+'’s source',sourceURL,credit:new URL(sourceURL).hostname.replace(/^www\./,''),score});
  });
  return photos.sort((a,b)=>b.score-a.score).slice(0,12);
 }
 async function load(venue,signal,refresh){
  const source=venue.photoSource||venue.website||venue.photo?.sourceURL||venue.commonsFile;
  if(!source)throw Error('No photo source is connected for this place yet.');
  const cached=cache.get(source);
  if(!refresh&&cached&&Date.now()-cached.at<TTL)return {...cached,cached:true};
  if(venue.commonsFile){try{const r=await fetch('/api/commons-photo?file='+encodeURIComponent(venue.commonsFile),{signal});if(r.ok){const data=await r.json();if(data.photos?.length){const result={...data,cached:false};if(cache.size>=12)cache.delete(cache.keys().next().value);cache.set(source,result);return result;}}}catch(e){if(signal?.aborted)throw e;}}
  if(!venue.website&&!venue.photoSource&&!venue.photo?.sourceURL)throw Error('The mapped photo is unavailable right now.');
  const response=await fetch('/api/photo-source?source='+encodeURIComponent(source)+(venue.live?'&placeId='+encodeURIComponent(venue.id):''),{signal,cache:refresh?'reload':'default'});
  if(!response.ok)throw Error('The photo source isn’t available right now.');
  const page=await response.json();
  const photos=extract(page.html,page.sourceURL,venue);
  if(!photos.length)throw Error('This source has no photos we can display for this place yet.');
  const result={photos,at:page.fetchedAt||Date.now(),cached:false};
  if(cache.size>=12)cache.delete(cache.keys().next().value);cache.set(source,result);
  return result;
 }
 function mount(p){
  pending?.abort();const controller=new AbortController();pending=controller;
  const root=document.querySelector('#place-gallery');if(!root)return;
  const venue=p.event?places.find(v=>v.id===p.venueId):p;
  if(!venue){root.innerHTML='<p class="gallery-status">No venue photo source is connected yet.</p>';return;}
  let photos=[],index=0;
  const active=()=>root.isConnected&&!controller.signal.aborted;
  const source=venue.photoSource||venue.website||venue.photo?.sourceURL;
  const sourceLabel=source?new URL(source).hostname.replace(/^www\./,''):venue.name;
  function go(i){index=Math.max(0,Math.min(photos.length-1,i));root.querySelector('.gallery-track')?.scrollTo({left:root.querySelector('.gallery-track').clientWidth*index,behavior:'smooth'});update();}
  function update(){root.querySelector('.gallery-count').textContent=(index+1)+' / '+photos.length;root.querySelectorAll('[data-photo-index]').forEach((b,i)=>b.setAttribute('aria-pressed',String(i===index)));root.querySelector('.gallery-prev').disabled=index===0;root.querySelector('.gallery-next').disabled=index===photos.length-1;root.querySelector('.gallery-caption').textContent=photos[index].caption;}
  function expand(){root.classList.toggle('expanded');root.querySelector('.gallery-expand').textContent=root.classList.contains('expanded')?'Back to place':'Enlarge photos';setTimeout(()=>go(index),50);}
  async function show(refresh=false){
   root.classList.remove('expanded');root.innerHTML='<div class="gallery-loading" role="status"><span class="gallery-spinner"></span>Finding photos of '+escapeHTML(venue.name)+'…</div>';
   try{
    const result=await load(venue,controller.signal,refresh);if(!active())return;
    photos=result.photos;index=0;
    root.innerHTML=`<div class="gallery-track" tabindex="0" aria-label="Photos of ${escapeHTML(venue.name)}">${photos.map((photo,i)=>`<figure><img src="${escapeHTML(photo.url)}" alt="${escapeHTML(photo.caption)}" referrerpolicy="no-referrer" ${i?'loading="lazy"':''}></figure>`).join('')}</div><div class="gallery-toolbar"><button class="gallery-prev" aria-label="Previous photo">‹</button><span class="gallery-count" aria-live="polite"></span><button class="gallery-next" aria-label="Next photo">›</button><button class="gallery-expand">Enlarge photos</button></div><div class="gallery-thumbs">${photos.map((photo,i)=>`<button data-photo-index="${i}" aria-label="View photo ${i+1}" aria-pressed="${i===0}"><img src="${escapeHTML(photo.url)}" alt="" referrerpolicy="no-referrer" loading="lazy"></button>`).join('')}</div><p class="gallery-caption"></p><p class="gallery-source">${p.event?'Venue photos · ':''}From <a href="${escapeHTML(photos[0].sourceURL)}" target="_blank" rel="noopener noreferrer">${escapeHTML(photos[0].credit||sourceLabel)}</a> · ${result.cached?'Loaded earlier this visit':'Fetched just now'} <button class="gallery-refresh">Refresh</button></p>`;
    root.querySelector('.gallery-prev').onclick=()=>go(index-1);root.querySelector('.gallery-next').onclick=()=>go(index+1);root.querySelector('.gallery-expand').onclick=expand;
    root.querySelector('.gallery-refresh').onclick=()=>show(true);
    root.querySelectorAll('[data-photo-index]').forEach(b=>b.onclick=()=>go(Number(b.dataset.photoIndex)));
    const track=root.querySelector('.gallery-track');track.addEventListener('scroll',()=>{index=Math.round(track.scrollLeft/track.clientWidth);update();},{passive:true});
    track.addEventListener('keydown',e=>{if(e.key==='ArrowRight'||e.key==='ArrowLeft'){e.preventDefault();go(index+(e.key==='ArrowRight'?1:-1));}});
    track.querySelectorAll('img').forEach(img=>{img.onclick=expand;img.onerror=()=>{const f=img.closest('figure');f.classList.add('photo-unavailable');img.hidden=true;f.textContent='This photo is unavailable. Try another or refresh.';};});
    update();
   }catch(e){if(!active())return;root.innerHTML=`<div class="gallery-status" role="status"><p>${escapeHTML(e.message)}</p><button class="outline-button gallery-retry">Try photos again</button></div>`;root.querySelector('.gallery-retry').onclick=()=>show(true);}
  }
  show();
 }
 document.querySelector('#dialog').addEventListener('cancel',e=>{const expanded=document.querySelector('#place-gallery.expanded');if(expanded){e.preventDefault();expanded.classList.remove('expanded');expanded.querySelector('.gallery-expand').textContent='Enlarge photos';}});
 window.RoamPhotos={mount,extract,load};
})();
