package net.voidflame.tab;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class VoidFlameTabPlugin extends JavaPlugin implements Listener {
    private TabManager manager;
    @Override public void onEnable(){
        saveDefaultConfig();
        manager=new TabManager(this);
        getServer().getPluginManager().registerEvents(this,this);
        long period=Math.max(1,getConfig().getLong("settings.update-interval-ticks",40));
        getServer().getScheduler().runTaskTimer(this,manager::updateAll,period,period);
        getServer().getScheduler().runTask(this,manager::updateAll);
        getLogger().info("VoidFlame-tab enabled | Scoreboard + TAB");
    }
    @EventHandler public void join(PlayerJoinEvent e){Bukkit.getScheduler().runTaskLater(this,()->manager.update(e.getPlayer()),2L);}
    @EventHandler public void quit(PlayerQuitEvent e){}
    @Override public boolean onCommand(CommandSender s,Command c,String l,String[] a){
        if(!s.hasPermission("voidflame.tab.admin")){s.sendMessage("§cNo permission.");return true;}
        if(a.length==0||a[0].equalsIgnoreCase("reload")){reloadConfig();manager.updateAll();s.sendMessage("§aVoidFlame TAB/Scoreboard reloaded.");return true;}
        s.sendMessage("§cUsage: /vftab reload");return true;
    }
}

final class TabManager {
    private final VoidFlameTabPlugin plugin;
    private final org.bukkit.scoreboard.ScoreboardManager bukkit;
    TabManager(VoidFlameTabPlugin p){plugin=p;bukkit=Bukkit.getScoreboardManager();}
    void updateAll(){ for(Player p:Bukkit.getOnlinePlayers()) update(p); }
    void update(Player p){
        if(bukkit==null)return;
        Object duels=Bukkit.getPluginManager().getPlugin("VoidFlame-Duels");
        String state=state(duels,p);
        boolean show=bool(duels,"playerSettings",p,"scoreboard",true);
        String path="scoreboard."+state;
        if(!plugin.getConfig().getBoolean("settings.scoreboard-enabled",true)||!plugin.getConfig().getBoolean(path+".enabled",true)||!show){
            p.setScoreboard(bukkit.getMainScoreboard());
        }else{
            Scoreboard board=bukkit.getNewScoreboard();
            Objective o=board.registerNewObjective("vf",org.bukkit.scoreboard.Criteria.DUMMY,color(plugin.getConfig().getString(path+".title","&5&lVOIDFLAME")));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            List<String> lines=plugin.getConfig().getStringList(path+".lines");
            Set<String> used=new HashSet<>();int score=lines.size();
            for(String line:lines){String x=render(line,duels,p);int max=Math.max(1,plugin.getConfig().getInt("settings.max-line-length",40));if(x.length()>max)x=x.substring(0,max);while(!used.add(x))x+=ChatColor.RESET;o.getScore(x.isBlank()?" ":x).setScore(score--);}
            p.setScoreboard(board);
        }
        if(plugin.getConfig().getBoolean("settings.tab-enabled",true)) updateTab(p,state,duels);
    }
    private String state(Object d,Player p){
        if(d==null)return "spawn";
        if(bool(d,"spectatorManager",p,"isSpectating",false))return "spectator";
        if(bool(d,"ffaManager",p,"isInFfa",false))return "ffa";
        if(bool(d,"queueManager",p,"isQueued",false))return "queue";
        Object match=call(d,"matchManager"); Object m=call(match,"get",p.getUniqueId());
        if(m!=null){matchFor(m);return "duel";}
        Object rem=call(d,"rematches");if(bool(rem,p,"hasRecent",false))return "post-match";
        return "spawn";
    }
    private void matchFor(Object m){}
    private void updateTab(Player p,String state,Object d){
        String header=color(plugin.getConfig().getString("tab.header","&5&lVOIDFLAME &8• &bPRACTICE"));
        String footer=color(plugin.getConfig().getString("tab.footer","&7%state% &8• &7Online: &f%server_online%"))
                .replace("%state%",state.replace('_',' ').toUpperCase(Locale.ROOT))
                .replace("%server_online%",String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%player_ping%",String.valueOf(p.getPing()))
                .replace("%party_status%",partyStatus(d,p));
        var legacy=LegacyComponentSerializer.legacySection();
        p.sendPlayerListHeaderAndFooter(legacy.deserialize(header),legacy.deserialize(footer));
        p.playerListName(legacy.deserialize(rankDisplay(p)));
    }
    private String render(String line,Object d,Player p){
        Object match=null;if(d!=null){Object mm=call(d,"matchManager");match=call(mm,"get",p.getUniqueId());}
        Stats s=stats(p);
        UUID opp = match == null ? null : (call(match,"opponent",p.getUniqueId()) instanceof UUID u ? u : null);
        return color(line)
                .replace("%server_online%",String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%practice_in_match%",String.valueOf(longVal(call(call(d,"matchManager"),"activeMatches"),0)*2))
                .replace("%practice_in_queue%",String.valueOf(longVal(call(call(d,"queueManager"),"totalQueued"),0)))
                .replace("%player_ping%",String.valueOf(p.getPing()))
                .replace("%opponent_ping%",opp==null?"-":String.valueOf(Optional.ofNullable(Bukkit.getPlayer(opp)).map(Player::getPing).orElse(-1)))
                .replace("%match_duration%",match==null?"0:00":duration(longVal(call(match,"durationSeconds"),0)))
                .replace("%arena_name%",match==null?"-":String.valueOf(call(call(match,"arena"),"name")))
                .replace("%kit_name%",match==null?"-":String.valueOf(call(match,"kit")))
                .replace("%opponent_name%",opp==null?"None":Optional.ofNullable(Bukkit.getPlayer(opp)).map(Player::getName).orElse("Opponent"))
                .replace("%player_wins%",String.valueOf(s.wins)).replace("%player_losses%",String.valueOf(s.losses))
                .replace("%player_streak%",String.valueOf(s.streak)).replace("%player_elo%",String.valueOf(Math.round(s.elo)))
                .replace("%player_kills%",String.valueOf(s.kills)).replace("%player_deaths%",String.valueOf(s.deaths))
                .replace("%queue_mode%",bool(call(d,"queueManager"),p,"isRanked",false)?"Ranked":"Unranked");
    }
    private String rankDisplay(Player p){
        String prefix="§7[Player]";
        try{
            Object service=service("net.voidflame.ranks.VoidFlameRanksPlugin$RankService");
            Object id=call(service,"getPlayerRankCached",p.getUniqueId());
            Object rank=call(service,"getRank",id);
            if(rank!=null){
                Object value=call(rank,"prefix");
                if(value!=null && !String.valueOf(value).isBlank()) prefix=color(String.valueOf(value));
            }
        }catch(Exception ignored){}
        return prefix+" §8• §f"+p.getName();
    }
    private String partyStatus(Object d,Player p){try{Object pm=call(d,"partyManager");return call(pm,"partyOf",p.getUniqueId())==null?"None":"In Party";}catch(Exception e){return "None";}}
    private Stats stats(Player p){
        try{Object s=service("net.voidflame.stats.StatsService");Object v=call(s,"getCached",p.getUniqueId());return new Stats(longVal(call(v,"wins"),0),longVal(call(v,"losses"),0),longVal(call(v,"kills"),0),longVal(call(v,"deaths"),0),longVal(call(v,"streak"),0),doubleVal(call(v,"elo"),1000));}catch(Exception e){return new Stats(0,0,0,0,0,1000);}
    }
    private record Stats(long wins,long losses,long kills,long deaths,long streak,double elo){}
    private Object service(String cn)throws Exception{Class<?> c=Class.forName(cn);var r=Bukkit.getServicesManager().getRegistration(c);return r==null?null:r.getProvider();}
    private Object await(Object o,String n,Object...a)throws Exception{return o==null?null:method(o,n,a).invoke(o,a);}
    private Object call(Object o,String n,Object...a){try{return o==null?null:method(o,n,a).invoke(o,a);}catch(Exception e){return null;}}
    private Method method(Object o,String n,Object...a){for(Method m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m;throw new IllegalArgumentException();}
    private boolean bool(Object o,String field,Player p,String method,boolean def){Object x=call(call(o,field),method,p);return x instanceof Boolean b?b:def;}
    private boolean bool(Object o,Player p,String method,boolean def){Object x=call(o,method,p);return x instanceof Boolean b?b:def;}
    private long longVal(Object x,long d){return x instanceof Number n?n.longValue():d;}
    private double doubleVal(Object x,double d){return x instanceof Number n?n.doubleValue():d;}
    private String duration(long s){return s/60+":"+String.format("%02d",s%60);}
    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s==null?"":s);}
}