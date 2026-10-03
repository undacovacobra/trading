// Shared browser policy. Android's PlanningPolicy mirrors these bounded time rules.
(function(root){const DAY=86400000;
 root.RoamPlanPolicy={
  due(t,last,now,finalSent=false){if(!t.every||now>=t.end||t.kind==='advance'&&now>=t.start)return false;
   const final=t.kind==='advance'&&now>=t.start-DAY&&!finalSent;
   if(final&&(!last||now-last>=DAY))return true;
   return now>=(last?last+t.every+(t.jitter||0):t.created+DAY+(t.jitter||0));
  },
  away(home,fix,at,now){if(!home||!fix||now-at>DAY||now<at)return false;const r=Math.PI/180,dlat=(fix.lat-home.lat)*r,dlng=(fix.lng-home.lng)*r,a=Math.sin(dlat/2)**2+Math.cos(home.lat*r)*Math.cos(fix.lat*r)*Math.sin(dlng/2)**2;return 3958.8*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a))>75;}
 };
})(globalThis);
