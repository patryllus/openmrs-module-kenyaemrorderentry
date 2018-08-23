package org.openmrs.module.orderentryui.page.controller;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openmrs.*;
import org.openmrs.api.EncounterService;
import org.openmrs.api.OrderService;
import org.openmrs.api.OrderSetService;
import org.openmrs.module.appui.UiSessionContext;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.representation.NamedRepresentation;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.ui.framework.UiUtils;
import org.openmrs.ui.framework.annotation.SpringBean;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DrugOrdersPageController {

    public void get(@RequestParam("patient") Patient patient,
                    @RequestParam(value = "careSetting", required = false) CareSetting careSetting,
                    @SpringBean("encounterService") EncounterService encounterService,
                    @SpringBean("orderService") OrderService orderService,
                    UiSessionContext sessionContext,
                    UiUtils ui,
                    PageModel model,
                    @SpringBean("orderSetService") OrderSetService orderSetService) {

        // HACK
        EncounterType drugOrderEncounterType = encounterService.getAllEncounterTypes(false).get(0);
        EncounterRole encounterRoles = encounterService.getAllEncounterRoles(false).get(0);

        List<CareSetting> careSettings = orderService.getCareSettings(false);

        List<Concept> dosingUnits = orderService.getDrugDosingUnits();
        List<Concept> dispensingUnits = orderService.getDrugDispensingUnits();
        Set<Concept> quantityUnits = new LinkedHashSet<Concept>();
        quantityUnits.addAll(dosingUnits);
        quantityUnits.addAll(dispensingUnits);

        List<OrderSet> orderSetsList=orderSetService.getOrderSets(false);
        JSONObject orderSetObj,orderSetMember;
        JSONArray orderSetArray=new JSONArray();

        for(OrderSet orderSet:orderSetsList){
            orderSetObj=new JSONObject();
            orderSetObj.put("name", orderSet.getName());
            orderSetObj.put("regimen_line", orderSet.getDescription());
            JSONArray membersArray=new JSONArray();
            for(OrderSetMember member:orderSet.getOrderSetMembers()){
                orderSetMember=new JSONObject();
                if(member !=null){
                    String[] template=member.getOrderTemplate().split(",");
                    orderSetMember.put("name",template[0]);
                    orderSetMember.put("dose",template[1]);
                    orderSetMember.put("units",template[2]);
                    orderSetMember.put("frequency",template[3]);
                    membersArray.add(orderSetMember);
                }
            }
            orderSetObj.put("components", membersArray);
            orderSetArray.add(orderSetObj);
        }
        JSONObject response=new JSONObject();
        response.put("orderSets",orderSetArray);
        model.put("orderSetJson",response.toString());

        Map<String, Object> jsonConfig = new LinkedHashMap<String, Object>();
        jsonConfig.put("patient", convertToFull(patient));
        jsonConfig.put("provider", convertToFull(sessionContext.getCurrentProvider()));
		jsonConfig.put("encounterRole", convertToFull(encounterRoles));
        jsonConfig.put("drugOrderEncounterType", convertToFull(drugOrderEncounterType));
        jsonConfig.put("careSettings", convertToFull(careSettings));
        jsonConfig.put("routes", convertToFull(orderService.getDrugRoutes()));
        jsonConfig.put("doseUnits", convertToFull(dosingUnits));
        jsonConfig.put("durationUnits", convertToFull(orderService.getDurationUnits()));
        jsonConfig.put("quantityUnits", convertToFull(dispensingUnits)); // after TRUNK-4524 is fixed, change this to quantityUnits
        jsonConfig.put("frequencies", convertTo(orderService.getOrderFrequencies(false), new NamedRepresentation("fullconcept")));
        if (careSetting != null) {
            jsonConfig.put("intialCareSetting", careSetting.getUuid());
        }

        model.put("patient", patient);
        model.put("jsonConfig", ui.toJson(jsonConfig));
    }

    private Object convertTo(Object object, Representation rep) {
        return object == null ? null : ConversionUtil.convertToRepresentation(object, rep);
    }

    private Object convertToFull(Object object) {
        return object == null ? null : ConversionUtil.convertToRepresentation(object, Representation.FULL);
    }

}
