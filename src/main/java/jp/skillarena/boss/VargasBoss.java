package jp.skillarena.boss;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import java.util.*;

/** Server-authoritative Vargas combat prototype. A packet player visual can replace the fallback entity. */
public final class VargasBoss implements Listener {
  private final JavaPlugin plugin;
  private final Random random = new Random();
  private Zombie body;
  private PacketVargasVisual visual;
  private BossBar bar;
  private BukkitTask ticker;
  private final Set<UUID> party = new HashSet<>();
  private final Map<UUID,Long> recentThreat = new HashMap<>();
  private final Map<UUID,Long> dodgeUntil = new HashMap<>();
  private double maxHp, currentHp;
  private int phase, ticks, fightTicks;
  private long nextAction, recoveryUntil;
  private String currentMove = "";
  private boolean pending;
  private final Map<String,Long> cooldowns = new HashMap<>();

  public VargasBoss(JavaPlugin plugin){this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin);}
  public boolean active(){return body!=null&&body.isValid()&&!body.isDead();}
  public boolean isParticipant(Player player){return active()&&party.contains(player.getUniqueId());}
  public void start(Player leader){
    if(active()){leader.sendMessage("§c剣帝ヴァルガスは既に出現しています。");return;}
    World world=leader.getWorld();Location spawn=leader.getLocation().clone().add(leader.getLocation().getDirection().setY(0).normalize().multiply(9));
    spawn.setY(world.getHighestBlockYAt(spawn)+1);
    party.clear();party.add(leader.getUniqueId());for(Player p:world.getPlayers())if(p.getGameMode()!=GameMode.SPECTATOR&&p.getLocation().distanceSquared(leader.getLocation())<=24*24&&party.size()<4)party.add(p.getUniqueId());
    int size=party.size();double scale=new double[]{0,1,1.75,2.4,3}[size];maxHp=2000*scale;currentHp=maxHp;
    body=world.spawn(spawn,Zombie.class,z->{z.setAdult();z.setAI(false);z.setSilent(true);z.setRemoveWhenFarAway(false);z.setCanPickupItems(false);z.setCustomName("§4剣帝ヴァルガス");z.setCustomNameVisible(true);z.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20);z.setHealth(20);z.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(.32);z.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));z.getEquipment().setHelmet(new ItemStack(Material.NETHERITE_HELMET));z.getEquipment().setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));z.getEquipment().setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));z.getEquipment().setBoots(new ItemStack(Material.NETHERITE_BOOTS));});
    // Use the real netherite-armored zombie model. No packet-based fake player visual.
    visual=null;
    phase=1;ticks=0;fightTicks=0;nextAction=0;recoveryUntil=0;pending=false;cooldowns.clear();dodgeUntil.clear();recentThreat.clear();
    bar=Bukkit.createBossBar("§4剣帝ヴァルガス §7| PHASE 1 §f"+(int)maxHp+"/"+(int)maxHp,BarColor.RED,BarStyle.SEGMENTED_10);
    for(UUID id:party){Player p=Bukkit.getPlayer(id);if(p!=null){bar.addPlayer(p);p.sendTitle("§4§l剣帝ヴァルガス","§7挑戦開始",10,45,15);}}
    ticker=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
  }
  private List<Player> players(){List<Player> out=new ArrayList<>();if(body==null)return out;for(UUID id:party){Player p=Bukkit.getPlayer(id);if(p!=null&&p.isOnline()&&!p.isDead()&&p.getWorld()==body.getWorld()&&p.getGameMode()!=GameMode.SPECTATOR&&p.getLocation().distanceSquared(body.getLocation())<65*65)out.add(p);}return out;}
  private Player target(){List<Player> ps=players();if(ps.isEmpty())return null;ps.sort(Comparator.comparingDouble(p->p.getLocation().distanceSquared(body.getLocation())-(recentThreat.getOrDefault(p.getUniqueId(),0L)>System.currentTimeMillis()?16:0)));return ps.get(0);}
  private void tick(){if(!active()){end(false);return;}fightTicks++;ticks++;if(fightTicks>=12000||players().isEmpty()){end(false);return;}
    double ratio=currentHp/maxHp;int next=ratio<=.20?3:ratio<=.50?2:1;
    if(next>phase){phase=next;recoveryUntil=ticks+35;pending=false;nextAction=ticks+45;body.getWorld().strikeLightningEffect(body.getLocation());body.getWorld().playSound(body.getLocation(),Sound.ENTITY_WITHER_SPAWN,1,.8f);for(Player p:players())p.sendTitle("§4PHASE "+phase,phase==3?"§cラグナロク解放":"§c上位剣技解放",5,35,10);}
    bar.setProgress(Math.max(0,Math.min(1,ratio)));bar.setTitle("§4剣帝ヴァルガス §7| PHASE "+phase+" §f"+(int)Math.ceil(currentHp)+"/"+(int)maxHp);
    if(ticks%2!=0)return;
    Player t=target();if(t==null)return;
    Location at=body.getLocation();Vector toward=t.getLocation().toVector().subtract(at.toVector());double distance=toward.length();if(distance<.1)return;
    Vector flat=toward.clone().setY(0);if(flat.lengthSquared()<.01)return;flat.normalize();
    body.setRotation((float)Math.toDegrees(Math.atan2(-flat.getX(),flat.getZ())),0);
    if(ticks<recoveryUntil||pending)return;
    // Stepwise movement with obstacle check, strafing and occasional evasive dash.
    if(distance>3.2||distance<2.0){Vector move=flat.clone().multiply(distance>3.2?.24:-.18);if(ticks%36<12)move.add(new Vector(-flat.getZ(),0,flat.getX()).multiply(.11));move(body,move);}
    else if(ticks%10==0){Vector side=new Vector(-flat.getZ(),0,flat.getX()).multiply((ticks/10)%2==0?.35:-.35);move(body,side);}
    if(ticks<nextAction)return;
    String move=choose(distance);if(move==null)return;startMove(move,t);
  }
  private void move(Zombie z,Vector delta){Location next=z.getLocation().clone().add(delta);if(next.getBlock().isPassable()&&next.clone().add(0,1,0).getBlock().isPassable()){Location below=next.clone().subtract(0,1,0);if(below.getBlock().isSolid())z.teleport(next);else if(next.clone().subtract(0,.65,0).getBlock().isSolid())z.teleport(next);} }
  private String choose(double distance){List<String> options=new ArrayList<>();options.add("BREAK");if(distance<6)options.add("BLAST");if(distance<9)options.add("TEMPEST");if(distance>4&&distance<16)options.add("GENOCIDE");if(phase==3&&ticks>160)options.add("RAGNAROK");Collections.shuffle(options,random);for(String s:options)if(cooldowns.getOrDefault(s,0L)<=ticks)return s;return null;}
  private void startMove(String name,Player target){currentMove=name;pending=true;int telegraph=name.equals("RAGNAROK")?40:name.equals("GENOCIDE")?16:12;
    long ct=switch(name){case "BREAK"->38;case "BLAST"->65;case "TEMPEST"->90;case "GENOCIDE"->75;default->240;};cooldowns.put(name,(long)ticks+ct);
    nextAction=ticks+telegraph+12;recoveryUntil=ticks+telegraph+12;
    Location origin=body.getLocation().clone();Vector dir=target.getLocation().toVector().subtract(origin.toVector()).setY(0).normalize();
    double range=name.equals("GENOCIDE")?13:name.equals("RAGNAROK")?14:name.equals("TEMPEST")?6:name.equals("BLAST")?5:4;
    Particle particle=phase>=2?Particle.SOUL_FIRE_FLAME:Particle.CRIT;
    body.getWorld().playSound(origin,Sound.ITEM_TRIDENT_RIPTIDE_1,.8f,.65f);
    for(int j=0;j<telegraph;j+=3){int step=j;Bukkit.getScheduler().runTaskLater(plugin,()->{if(!active()||!pending||!currentMove.equals(name))return;Location at=body.getLocation();for(double d=1;d<range;d+=1.3){Location point=at.clone().add(dir.clone().multiply(d)).add(0,.25,0);body.getWorld().spawnParticle(particle,point,7,.3,.2,.3,.025);}},step);}
    Bukkit.getScheduler().runTaskLater(plugin,()->{if(!active()||!pending||!currentMove.equals(name))return;pending=false;execute(name,dir);},telegraph);
  }
  private void execute(String move,Vector dir){if(visual!=null)visual.swing();Location origin=body.getLocation().clone();World w=origin.getWorld();
    w.playSound(origin,Sound.ENTITY_PLAYER_ATTACK_SWEEP,1.6f,.6f);
    w.spawnParticle(Particle.CRIT,origin.clone().add(0,1,0),85,2,.8,2,.15);
    w.spawnParticle(Particle.SOUL_FIRE_FLAME,origin.clone().add(0,1,0),55,2,.8,2,.08);
    double damage=phase==1?10:phase==2?13:16;
    if(move.equals("RAGNAROK")){w.strikeLightningEffect(origin);damage=24;}
    if(move.equals("GENOCIDE")){for(int i=0;i<24;i++){Location candidate=origin.clone().add(dir.clone().multiply((i+1)*.45));if(!candidate.getBlock().isPassable()||!candidate.clone().add(0,1,0).getBlock().isPassable())break;body.teleport(candidate);}w.spawnParticle(Particle.SWEEP_ATTACK,body.getLocation().add(0,1,0),18,1,.4,1,0);}
    else if(move.equals("TEMPEST")){w.spawnParticle(Particle.EXPLOSION_LARGE,origin.clone().add(0,1,0),3,1,.3,1,0);}
    if(move.equals("BLAST")||move.equals("TEMPEST")||move.equals("RAGNAROK")){
      double radius=move.equals("RAGNAROK")?14:move.equals("TEMPEST")?6:5;
      for(int i=0;i<60;i++){double angle=i*Math.PI/30;
        w.spawnParticle(Particle.CLOUD,origin.clone().add(Math.cos(angle)*radius,.4,Math.sin(angle)*radius),3,.1,.15,.1,.03);}
      w.spawnParticle(Particle.EXPLOSION_NORMAL,origin.clone().add(0,1,0),75,1,.5,1,.13);
      w.playSound(origin,Sound.ENTITY_GENERIC_EXPLODE,1.1f,.85f);
    }
    else w.spawnParticle(Particle.SWEEP_ATTACK,origin.clone().add(0,1,0),22,2,.5,2,.04);
    Set<UUID> struck=new HashSet<>();for(Player p:players()){
      Vector delta=p.getLocation().toVector().subtract(origin.toVector()).setY(0);double along=delta.dot(dir);double sideways=delta.clone().subtract(dir.clone().multiply(along)).length();
      boolean hit=switch(move){case "BREAK"->along>=0&&along<=4&&sideways<1.5;case "BLAST"->delta.length()<5;case "TEMPEST"->delta.length()<6;case "GENOCIDE"->along>=0&&along<14&&sideways<2;default->delta.length()<14;};
      if(!hit||!struck.add(p.getUniqueId())||System.currentTimeMillis()<dodgeUntil.getOrDefault(p.getUniqueId(),0L))continue;
      if(!p.hasLineOfSight(body)&&!move.equals("TEMPEST"))continue;
      p.damage(damage,body);
      if(move.equals("BLAST")||move.equals("TEMPEST")||move.equals("RAGNAROK")){
        Vector away=p.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if(away.lengthSquared()<.0001)away=dir.clone();
        away.normalize();double force=move.equals("RAGNAROK")?1.55:move.equals("TEMPEST")?1.25:1.05;
        p.setVelocity(new Vector(away.getX()*force,move.equals("RAGNAROK")?.85:.65,away.getZ()*force));
      }
      if(move.equals("BLAST")||move.equals("RAGNAROK"))p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,30,1));
    }
  }
  public void registerDodge(Player p,long until){dodgeUntil.put(p.getUniqueId(),until);}
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void hurt(EntityDamageEvent event){if(body==null||!event.getEntity().getUniqueId().equals(body.getUniqueId()))return;
    if(!(event instanceof EntityDamageByEntityEvent)){event.setCancelled(true);return;}
    if(event instanceof EntityDamageByEntityEvent by){Entity source=by.getDamager();if(source instanceof Projectile projectile&&projectile.getShooter() instanceof Player shooter)source=shooter;
      if(source instanceof Player p){if(!party.contains(p.getUniqueId())){event.setCancelled(true);return;}recentThreat.put(p.getUniqueId(),System.currentTimeMillis()+4000);}}
    event.setCancelled(true);
    // Read pre-armor damage: netherite is purely cosmetic for Vargas.
    double amount=Math.max(0,event.getDamage());
    if(amount<=0)return;
    currentHp=Math.max(0,currentHp-amount);
    if(currentHp<=0)end(true);
  }
  @EventHandler public void death(EntityDeathEvent e){if(body!=null&&e.getEntity().getUniqueId().equals(body.getUniqueId())){e.getDrops().clear();end(true);}}
  @EventHandler public void quit(PlayerQuitEvent e){if(bar!=null)bar.removePlayer(e.getPlayer());}
  public void end(boolean win){if(visual!=null){visual.close();visual=null;}if(ticker!=null){ticker.cancel();ticker=null;}if(bar!=null){bar.removeAll();bar=null;}if(body!=null){Location at=body.getLocation();if(win){at.getWorld().playSound(at,Sound.UI_TOAST_CHALLENGE_COMPLETE,1,1);for(UUID id:party){Player p=Bukkit.getPlayer(id);if(p!=null){p.sendTitle("§6討伐成功","§e剣帝ヴァルガス",10,55,15);if(plugin instanceof jp.skillarena.SkillArenaPlugin arena)arena.rewardBossAccessory(p);}}}else for(UUID id:party){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage("§cヴァルガス戦終了（敗北・時間切れ）");}body.remove();body=null;}pending=false;party.clear();}
}
