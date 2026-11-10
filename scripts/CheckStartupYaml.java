import java.nio.file.*;
import java.io.InputStream;
import java.util.Map;
import java.util.List;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Syntax/loading policy check only. Never starts Spring or loads private configuration. */
public class CheckStartupYaml {
  static Map<?,?> mapping(Object value) {
    if (!(value instanceof Map<?,?> m)) throw new IllegalStateException("YAML mapping required");
    return m;
  }
  public static void main(String[] args) throws Exception {
    Path resources=Path.of(args[0]).resolve("Qingfeng_AI_Music/qf-server/src/main/resources");
    Yaml yaml=new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<?,?> base, mysql;
    try(InputStream in=Files.newInputStream(resources.resolve("application.yml"))) {base=mapping(yaml.load(in));}
    try(InputStream in=Files.newInputStream(resources.resolve("application-mysql.yml"))) {mysql=mapping(yaml.load(in));}
    Map<?,?> spring=mapping(base.get("spring"));
    Object imports=mapping(spring.get("config")).get("import");
    if (!(imports instanceof List<?> list) || list.stream().anyMatch(x->!(x instanceof String s && s.startsWith("classpath:"))))
      throw new IllegalStateException("Implicit external configuration import");
    Map<?,?> mysqlSpring=mapping(mysql.get("spring"));
    if(mysqlSpring.containsKey("config")) throw new IllegalStateException("Unexpected mysql config import");
    if(!("$"+"{MYSQL_URL}").equals(mapping(mysqlSpring.get("datasource")).get("url")))
      throw new IllegalStateException("Unexpected default database address");
    System.out.println("YAML_SYNTAX_AND_EXPLICIT_CONFIG_PASS; no Spring startup or DB connection");
  }
}
