// Each tab owns a task. Browsing ideas is an explicit step within planning tabs.
(function(){
 const beforeRender=render,beforeFeed=renderFeed,beforeChange=changeView;
 const beforeRecommendations=recommendations,beforeOrigin=plannedOrigin;
 const beforeCalendar=renderTravelCalendar,beforeRoute=renderRoute,beforeProfile=renderCompanionProfile;
 let ideasFor=null,limit=6;
 const eventFilters={area:'Colorado',from:today(),until:''};
 const browsing=()=>['discover','saved','events'].includes(state.view)||(state.view==='route'&&!!state.route)||(['calendar','trips'].includes(state.view)&&ideasFor===state.view&&(state.view!=='trips'||!!activeTrip()));
 window.RoamFocus={limit:()=>state.view==='route'?3:limit};
 $('.sidebar nav').insertAdjacentHTML('beforeend',`<button class="nav-item" data-view="events">${icon('music')}Events</button>`);
 $('#feed-section').insertAdjacentHTML('beforebegin','<section id="focus-context" class="focus-context" hidden></section><section id="events-panel" class="events-panel" hidden></section>');
 $('#feed-section').insertAdjacentHTML('beforeend','<button id="focus-more" class="outline-button focus-more" hidden>Show a few more</button>');
 // Keep settings and preferences off the nearby-ideas home page.
 const settingsNav=$('.nav-item[data-view="profile"]');settingsNav.innerHTML=icon('sliders')+'Settings';
 $('.top-actions [data-view="profile"]').setAttribute('aria-label','Settings');
 $('.user-profile strong').textContent='Settings';$('.user-profile small').textContent='Your style & preferences';
 categories[4]=['Events','music'];
 changeView=view=>{
  ideasFor=null;limit=6;
  if(view==='events'){state.view=view;state.category='For you';state.query='';$('#search').value='';render();window.scrollTo({top:0,behavior:'smooth'});}
  else beforeChange(view);
 };
 plannedOrigin=()=>state.view==='events'&&eventFilters.area!=='Colorado'?locations[eventFilters.area]:beforeOrigin();
 recommendations=()=>{
  if(state.view!=='events')return beforeRecommendations();
  if(eventFilters.until&&eventFilters.from>eventFilters.until)return[];
  return places.filter(p=>{
   const reply=replyFor(p.id);
   return p.event&&p.eventDate>=eventFilters.from&&(!eventFilters.until||p.eventDate<=eventFilters.until)&&new Date(p.start).getTime()+4*3600000>Date.now()
    &&distance(plannedOrigin(),p)<=(eventFilters.area==='Colorado'?100:50)
    &&(!state.query||`${p.name} ${p.city} ${p.tags.join(' ')}`.toLowerCase().includes(state.query.toLowerCase()))
    &&!(reply?.feeling==='no'&&reply.reason==='taste')&&!(reply?.feeling==='later'&&Date.now()-reply.at<86400000)
    &&(travelCalendar.showConflicts||!eventConflict(p));
  }).sort((a,b)=>state.sort==='distance'?recommendationDistance(a)-recommendationDistance(b):new Date(a.start)-new Date(b.start));
 };
 renderFeed=()=>{
  const visible=browsing();$('#feed-section').hidden=!visible;
  if(!visible){$('#feed').textContent='';$('#focus-more').hidden=true;return;}
  beforeFeed();
  $('#categories').hidden=!['discover','saved'].includes(state.view);
  // Trips and calendar have their own context, not global nearby-place controls.
  $('.feed-controls').hidden=!['discover','saved'].includes(state.view);
  const count=RoamFocus.limit(),cards=[...$('#feed').querySelectorAll('.place-card')];
  cards.slice(count).forEach(card=>card.remove());
  document.querySelectorAll('#focus-context [data-category]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.category===state.category)));
  $('#focus-more').hidden=state.view==='route'||recommendations().length<=count;
  if(state.view==='events'&&!cards.length){$('#feed').innerHTML='<div class="empty-state"><h3>No events for these dates.</h3><p>Try another date range or area. Current listings cover Red Rocks.</p></div>';}
  if(['calendar','trips'].includes(state.view)){
   const suffix=state.view==='calendar'?'from this day’s planned location':'from trip base';
   $('#feed').querySelectorAll('.card-distance').forEach(el=>{el.innerHTML=el.innerHTML.replace(/from trip base|away/,suffix);});
  }
  if(state.view==='events'&&eventFilters.area!=='Colorado')$('#feed').querySelectorAll('.card-distance').forEach(el=>{el.innerHTML=el.innerHTML.replace('away','from '+eventFilters.area);});
 };
 renderCompanionProfile=()=>{
  beforeProfile();$('.profile-summary h2').textContent='Your travel style';
  $('.profile-summary p').textContent='Your introduction and the preferences that shape suggestions.';
  $('#profile-panel').insertAdjacentHTML('beforeend',`<section class="settings-connections"><h2>Notifications & calendar</h2><p>Choose when Roam nudges you and check calendar availability.</p><button class="outline-button" data-focus="nudge-settings">Nudge settings</button><button class="outline-button" data-focus="calendar-settings">Calendar & availability</button></section>`);
 };
 renderRoute=()=>{
  beforeRoute();
  const root=$('#route-panel'),columns=[...root.children];if(columns.length<2)return;
  const intro=columns[0],setup=columns[1];
  const help=document.createElement('details');help.className='focus-help';help.innerHTML='<summary>About Google Maps sharing</summary>';
  [...intro.querySelectorAll('.connection-note,.fine-print')].forEach(el=>help.append(el));
  if(state.route){
   const actions=document.createElement('div');actions.className='route-focused-actions';
   [...setup.querySelectorAll('.route-status,.maps-link,.route-clear')].forEach(el=>actions.append(el));
   const change=document.createElement('details');change.className='focus-help route-destination-edit';change.innerHTML='<summary>Change destination</summary>';
   while(setup.firstChild)change.append(setup.firstChild);setup.append(actions,change);
   intro.querySelector('h2').textContent='Heading to '+state.route.name;
   intro.querySelector('p').textContent='A few stops that fit this route and your detour time.';
  }else{
   intro.querySelector('h2').textContent='Where are you going?';
   intro.querySelector('p').textContent='Choose a destination to see stops along the way.';
  }
  intro.append(help);
 };
 renderTravelCalendar=()=>{
  beforeCalendar();
  const root=$('#calendar-panel'),connection=root.querySelector('.calendar-connection'),help=root.querySelector('.calendar-import-help');
  const connections=document.createElement('details');connections.className='focus-help calendar-availability-settings';
  connections.innerHTML='<summary>Calendar connection & availability</summary>';
  if(connection)connections.append(connection);if(help)connections.append(help);
  root.append(connections);
  // Conflict preferences belong with browsing ideas, not with entering a schedule.
  const settings=root.querySelector('.calendar-suggestion-settings');if(settings)connections.append(settings);
  root.querySelector('.calendar-agenda h2').textContent='Your schedule';
  root.querySelector('.calendar-agenda').insertAdjacentHTML('beforeend','<button class="outline-button day-ideas-button" data-focus="day-ideas">Find ideas for this day</button>');
 };
 function eventsPanel(){
  $('#events-panel').innerHTML=`<div class="event-date-controls"><label>Area<select id="event-area" class="form-select">${['Colorado','Boulder','Denver','Golden'].map(area=>`<option ${area===eventFilters.area?'selected':''}>${area}</option>`).join('')}</select></label><label>From<input id="event-from" class="form-input" type="date" min="${today()}" value="${eventFilters.from}"></label><label>Until (optional)<input id="event-until" class="form-input" type="date" min="${eventFilters.from}" value="${eventFilters.until}"></label></div><p id="event-filter-error" class="event-filter-error" role="alert" hidden>Choose an end date on or after the start date.</p><p class="events-source">Current coverage: official Red Rocks events. Times are shown in Mountain Time.</p>`;
  for(const [id,key]of [['event-area','area'],['event-from','from'],['event-until','until']])$('#'+id).addEventListener('change',e=>{
   eventFilters[key]=e.target.value;if(key==='from'&&!eventFilters.from){eventFilters.from=today();e.target.value=eventFilters.from;}
   const invalid=!!eventFilters.until&&eventFilters.from>eventFilters.until;
   $('#event-filter-error').hidden=!invalid;$('#event-until').setCustomValidity(invalid?'Choose an end date on or after the start date.':'');
   $('#event-until').min=eventFilters.from;limit=6;renderFeed();
  });
 }
 function context(){
  const root=$('#focus-context');root.hidden=true;root.textContent='';
  if(ideasFor===state.view&&['calendar','trips'].includes(state.view)&&browsing()){
   const trip=activeTrip(),calendar=state.view==='calendar';
   root.hidden=false;root.innerHTML=`<div class="focus-context-heading"><button class="text-button focus-back" data-focus="back">${calendar?'Back to calendar':'Back to my trips'}</button><div><strong>${escapeHTML(calendar?dateLabel(travelCalendar.selectedDate):trip.name)}</strong><p>${calendar?escapeHTML(plannedOrigin().name):escapeHTML(trip.area)+' · '+dateLabel(trip.start)+'–'+dateLabel(trip.end)}</p></div></div><div class="focus-idea-kind" aria-label="Kinds of ideas"><button class="outline-button" data-category="For you" aria-pressed="${state.category!=='Events'}">Places & events</button><button class="outline-button" data-category="Events" aria-pressed="${state.category==='Events'}">Events only</button></div>`;
   root.insertAdjacentHTML('beforeend',`<details class="focus-help"><summary>Calendar availability checks</summary><label class="focus-conflicts-label"><input id="focus-show-conflicts" type="checkbox" ${travelCalendar.showConflicts?'checked':''}>Show events that may conflict with my plans</label><p>Overlap checks allow an estimated 3 hours when an event has no end time.</p></details>`);
   $('#focus-show-conflicts').addEventListener('change',e=>{travelCalendar.showConflicts=e.target.checked;calendarSave();renderFeed();});
  }
 }
 render=()=>{
  beforeRender();
  $('#companion-status').hidden=true;$('#companion-status').textContent='';$('#personalize').hidden=true;
  $('#events-panel').hidden=state.view!=='events';
  $('#feed-section').hidden=!browsing();
  $('.search-box').hidden=!browsing();
  $('#categories').hidden=!['discover','saved'].includes(state.view);
  $('.feed-controls').hidden=!['discover','saved'].includes(state.view);
  $('.eyebrow').textContent=({discover:'NEAR YOU',calendar:'YOUR SCHEDULE',trips:'YOUR PLANS',route:'YOUR ROUTE',events:'UPCOMING EVENTS',saved:'KEPT FOR LATER',profile:'SETTINGS',inbox:'SUGGESTIONS & RESPONSES'})[state.view];
  const titles={discover:['Nearby, for you.','Ideas around your current location, matched to your mood.'],calendar:['Travel calendar','Keep track of where you’ll be and when.'],trips:['My trips','Add or edit your travel dates and destinations.'],route:['On my way','A destination and a few worthwhile stops.'],events:['Upcoming events','Choose where and when you’d like to go.'],profile:['Settings','Your travel style, preferences, and nudge settings.'],inbox:['Suggestions & responses','Review your companion’s nudges and what you thought of them.'],saved:['Saved places & events','The ideas you chose to keep for later.']};
  $('#page-title').textContent=titles[state.view][0];$('#page-subtitle').textContent=titles[state.view][1];
  $('#search').placeholder=state.view==='events'?'Search events':state.view==='saved'?'Search saved ideas':'Search these ideas';
  $('#search').setAttribute('aria-label',$('#search').placeholder);
  if(state.view==='discover'){
   $('.feature-grid').classList.add('focused-home');$('.journey-card').hidden=true;
   $('#feed-title').textContent='A few nearby ideas';$('#feed-subtitle').textContent='Choose one that feels right. You can ask for more.';
   $('#categories [data-category="Events"]').hidden=true;
  }else if(state.view==='calendar'){
   $('#calendar-panel').hidden=ideasFor==='calendar';
   if(ideasFor==='calendar'){$('#page-title').textContent='Ideas for this day';$('#page-subtitle').textContent='Places near your planned location and events that fit this date.';$('#feed-title').textContent='For '+dateLabel(travelCalendar.selectedDate);}
  }else if(state.view==='trips'){
   $('#trips-panel').hidden=ideasFor==='trips'&&!!activeTrip();
   if(ideasFor==='trips'&&activeTrip()){$('#page-title').textContent='Ideas for this trip';$('#page-subtitle').textContent='Places near your destination and events during your travel dates.';$('#feed-title').textContent='For '+activeTrip().name;}
   else{
    $('.trip-toolbar p').textContent='Your dates and destinations, in one place.';
    $('#trips-panel .source-status')?.remove();
    $('#trips-panel').querySelectorAll('.trip-card').forEach(card=>{const id=card.querySelector('[data-select-trip]').dataset.selectTrip;card.insertAdjacentHTML('beforeend',`<button class="outline-button trip-ideas-button" data-focus="trip-ideas" data-trip="${id}">See ideas for this trip</button>`);});
   }
  }else if(state.view==='route'){$('#feed-title').textContent='A few stops along this route';$('#feed-subtitle').textContent='Within your chosen detour time. Distances are estimates.';}
  else if(state.view==='events'){eventsPanel();$('#feed-title').textContent='Events for these dates';$('#feed-subtitle').textContent='Upcoming events only, with dates and venue details.';renderFeed();}
  context();
  const more=$('[data-calendar="more-menu"]');more.classList.toggle('active',['saved','profile','inbox','events'].includes(state.view));
  document.querySelectorAll('.nav-item[data-view]').forEach(b=>{if(b.dataset.view===state.view)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');});
 };
 $('#focus-more').onclick=()=>{limit+=6;renderFeed();};
 document.addEventListener('click',e=>{
  const button=e.target.closest('[data-focus]');if(!button)return;
  e.preventDefault();e.stopImmediatePropagation();
  if(button.dataset.focus==='trip-ideas'){companion.activeTrip=button.dataset.trip;persist();ideasFor='trips';limit=6;render();window.scrollTo({top:0,behavior:'smooth'});}
  if(button.dataset.focus==='day-ideas'){ideasFor='calendar';limit=6;render();window.scrollTo({top:0,behavior:'smooth'});}
  if(button.dataset.focus==='back'){ideasFor=null;render();window.scrollTo({top:0,behavior:'smooth'});}
  if(button.dataset.focus==='nudge-settings')settingsDialog();
  if(button.dataset.focus==='calendar-settings'){changeView('calendar');$('.calendar-availability-settings').open=true;$('.calendar-availability-settings').scrollIntoView({behavior:'smooth',block:'nearest'});}
 },true);
 // The existing mobile menu is generated in a dialog; keep it small and labeled.
 const dialogObserver=new MutationObserver(()=>{
  const settings=$('[data-menu-view="profile"]');if(settings&&!settings.dataset.focusLabel){settings.dataset.focusLabel='true';settings.innerHTML='Settings '+icon('sliders');}
  if($('[data-menu-view="saved"]')&&!$('[data-menu-view="events"]'))$('[data-menu-view="saved"]').insertAdjacentHTML('afterend',`<button class="location-option" data-menu-view="events">Upcoming events ${icon('music')}</button>`);
 });
 dialogObserver.observe($('#dialog-content'),{childList:true,subtree:true});
 render();
})();
