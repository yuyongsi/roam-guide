package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** A private, consistent, read-only snapshot; budget is opt-in. */
@RestController
@RequestMapping("/api/me/trips/{id}/print")
public class TripPrintController {
    private final Database db;
    private final FeatureController trips;
    private final PlannerDepthController schedules;
    private final TripMealController meals;
    private final BudgetController budgets;
    public TripPrintController(Database db, FeatureController trips, PlannerDepthController schedules, TripMealController meals, BudgetController budgets) {
        this.db=db; this.trips=trips; this.schedules=schedules; this.meals=meals; this.budgets=budgets;
    }
    @GetMapping ResponseEntity<?> snapshot(@PathVariable long id, @RequestParam(defaultValue="false") boolean budget, HttpServletRequest req) {
        Object result=db.transaction(status -> {
            var trip=trips.trip(id,req);
            var schedule=schedules.get(id,req);
            @SuppressWarnings("unchecked") var mealData=(Map<String,Object>) meals.list(id,req);
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>) mealData.get("items");
            var cleanMeals=new ArrayList<Map<String,Object>>();
            for(var row:rows) {
                var clean=new LinkedHashMap<String,Object>();
                for(String key:List.of("day_number","slot","title","city_name","spot_id","spot_title","spot_in_trip","notes")) clean.put(key,row.get(key));
                if(budget) clean.put("planned_cents",row.get("planned_cents"));
                cleanMeals.add(clean);
            }
            var out=new LinkedHashMap<String,Object>();
            out.put("trip",trip); out.put("schedule",schedule); out.put("meals",cleanMeals);
            if(budget) out.put("budget",budgets.get(id,req));
            return out;
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
