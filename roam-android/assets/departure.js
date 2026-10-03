// A visit can be anywhere: departure detection never depends on a known venue.
class RoamDeparture {
 constructor(){this.last=null;this.outside=null;this.qualified=false;}
 update(lat,lng,accuracy,at){
  if(!Number.isFinite(lat)||!Number.isFinite(lng)||!Number.isFinite(accuracy)||accuracy<=0||accuracy>60)return false;
  if(this.last===null||at<this.last||at-this.last>900000){this.reset(lat,lng,at);return false;}
  this.last=at;const distance=this.meters(this.lat,this.lng,lat,lng);
  if(distance<=100+accuracy){this.outside=null;this.qualified=this.qualified||at-this.arrived>=600000;return false;}
  if(!this.qualified){this.reset(lat,lng,at);return false;}
  if(distance<=200+accuracy){this.outside=null;return false;}
  if(this.outside===null){this.outside=at;return false;}
  if(at-this.outside<30000)return false;
  this.departed={lat:this.lat,lng:this.lng};this.reset(lat,lng,at);return true;
 }
 reset(lat,lng,at){this.lat=lat;this.lng=lng;this.arrived=this.last=at;this.outside=null;this.qualified=false;}
 meters(a,b,c,d){const x=(c-a)*Math.PI/180,y=(d-b)*Math.PI/180,h=Math.sin(x/2)**2+Math.cos(a*Math.PI/180)*Math.cos(c*Math.PI/180)*Math.sin(y/2)**2;return 6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));}
}
