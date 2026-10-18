package arrow.binge.arrow_binge_backend;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class Catalog {
 public static final String VERSION="discovery-v3";
 private final Map<Integer,Integer> next=new HashMap<>(),counts=new HashMap<>();
 public Catalog() throws Exception {try(var stream=getClass().getResourceAsStream("/catalog.json")){for(var node:Json.MAPPER.readTree(stream)){int id=node.get("id").asInt();counts.put(id,node.get("arrows").asInt());if(!node.get("next").isNull())next.put(id,node.get("next").asInt());}}}
 public int count(int id){if(!counts.containsKey(id))throw new ApiException(400,"UNKNOWN_LEVEL");return counts.get(id);}
 public Integer next(int id){return next.get(id);}
}
