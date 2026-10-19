package arrow.binge.arrow_binge_backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogRevisionTest {
 @Test void starterRevisionsDoNotReinterpretExistingArrowIds() throws Exception {
  var catalog=new Catalog();
  assertEquals(4,catalog.count(1,"starter-v1"));
  assertEquals(6,catalog.count(2,"starter-v1"));
  assertEquals(8,catalog.count(4,"starter-v1"));
  assertEquals(28,catalog.count(1,"discovery-v3"));
  assertEquals(30,catalog.count(2,"discovery-v3"));
  assertEquals(31,catalog.count(4,"discovery-v3"));
  assertThrows(ApiException.class,()->catalog.count(451,"starter-v1"));
  assertThrows(ApiException.class,()->catalog.count(1,"unknown"));
 }
}
