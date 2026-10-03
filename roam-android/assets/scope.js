// Rank the wider activity catalog using the relevant introduction branches.
(function(){
 const beforeScore=score,beforeRecommendations=recommendations;score=p=>beforeScore(p)+activityMatch(p);
 recommendations=()=>{let at=Date.now();if(state.view==='trips'&&activeTrip())at=zonedTimeToUTC(activeTrip().start+'T12:00',activeTrip().zone||ROAM_ZONE);if(state.view==='calendar')at=calendarDayBounds().start;return beforeRecommendations().filter(p=>activityEligible(p,at));};
 const previews=new Map();
 function previewPhotos(){document.querySelectorAll('[data-runtime-photo]').forEach(node=>{const p=places.find(p=>p.id===node.dataset.runtimePhoto);if(!p||node.dataset.started)return;node.dataset.started='true';let job=previews.get(p.id);if(!job){job=RoamPhotos.load(p,AbortSignal.timeout(18000));previews.set(p.id,job);job.catch(()=>previews.delete(p.id));setTimeout(()=>previews.delete(p.id),4*3600000);}
  job.then(result=>{if(!node.isConnected)return;const img=document.createElement('img');img.alt='Sourced photo of '+p.name;img.loading='lazy';img.onerror=()=>{node.textContent='Photos unavailable right now';};node.textContent='';node.appendChild(img);const credit=document.createElement('small');credit.className='runtime-photo-credit';credit.textContent='Photo: '+result.photos[0].credit||new URL(p.photoSource||p.website).hostname.replace(/^www\./,'');node.appendChild(credit);img.src=result.photos[0].url;}).catch(()=>{if(node.isConnected)node.textContent='Photos unavailable right now · open for retry';});
 });}
 const beforeFeed=renderFeed,beforeHome=renderCompanionHome;renderFeed=()=>{beforeFeed();previewPhotos();};renderCompanionHome=()=>{beforeHome();previewPhotos();};
})();
