package org.openmrs.module.kenyaemrorderentry.labDataExchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.*;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.kenyaemrorderentry.api.service.KenyaemrOrdersService;
import org.openmrs.module.kenyaemrorderentry.queue.LimsQueue;
import org.openmrs.module.kenyaemrorderentry.queue.LimsQueueStatus;
import org.openmrs.module.kenyaemrorderentry.util.Utils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A class for mapping lab tests and results between a labware facility-wide implementation and the EMR
 * TODO: abstract the key methods into a parent class and have various lab systems implement the specifics
 */
public class LabwareFacilityWideResultsMapper {
	public static final String LAB_TEST_RESULT_SET_PROPERTY = "result";
	public static String LAB_ENCOUNTER_TYPE_UUID = "e1406e88-e9a9-11e8-9f32-f2801f1b9fd1";
	private static Boolean debugMode = false;

	public LabwareFacilityWideResultsMapper() {
	}

	/**
	 * Reads the mapping file into a json object with a hash map structure
	 * The object has concept uuid as keys and an object as value.
	 * A structure for a simple test i.e. Malaria smear can be as below.
	 * <>
	 * "34567AAAAAAAA":{
	 * "testName":"CD4 count",
	 * }
	 * </>
	 * For lab sets i.e. complete blood count, the structure should look like the below:
	 * <p>
	 * {
	 * "34567AAAAAAAA":{
	 * "testName":"Malaria Smear",
	 * "result": {
	 * "Positive":"703AAAAAAAAAAA",
	 * "Negative":"664AAAAAAAAAAA"
	 * }
	 * },
	 * "34588AAAAAAAA":{
	 * "testName":"Full Blood count",
	 * "result": {
	 * "WBC":"7987AAAAAAAAAAA",
	 * "RDW":"79673AAAAAAAAAAA"
	 * }
	 * }
	 * }
	 *
	 * @return
	 */
	public static ObjectNode readLabTestMappingConfiguration() {
		AdministrationService administrationService = Context.getAdministrationService();
		String limsConfiguration = (administrationService.getGlobalProperty("kenyaemrorderentry.facilitywidelims.mapping"));
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode testConfiguration = null;
		try {
			testConfiguration = (ObjectNode) mapper.readTree(limsConfiguration);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return testConfiguration;
	}

	/**
	 * Processes results from LIMS
	 *
	 * @param resultPayload
	 * @return
	 */
	public static ResponseEntity<String> processResultsFromLims(String resultPayload) {
		debugMode = labsUtils.isLoggingEnabled();
		if (debugMode) System.out.println("Start Processing results from LIMs" + resultPayload);
		JsonElement rootNode = JsonParser.parseString(resultPayload);
		JsonObject resultsObj = null;
		try {
			if (rootNode.isJsonObject()) {
				resultsObj = rootNode.getAsJsonObject();
				if (debugMode) System.out.println("Result object" + resultPayload);
			} else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("The payload could not be understood. An object is expected!");
			}
		} catch (Exception e) {
			e.printStackTrace();
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("An error occured: " + e.getMessage());
		}
		
		if (resultsObj != null) {
			Map<String, String> resultMap = new HashMap<>();
			JsonArray resultArray = resultsObj.get("data").getAsJsonArray();			
			Integer orderId = null;
			for (int i = 0; i < resultArray.size(); i++) {
				try {
					JsonObject o = resultArray.get(i).getAsJsonObject();
					orderId = o.get("identifier").getAsInt();					
					String testName = !o.isJsonNull() && !o.get("valueCode").isJsonNull() ? o.get("valueCode").getAsString() : "";
					String result = !o.isJsonNull() && !o.get("value").isJsonNull() ? o.get("value").getAsString() : "";
					if (StringUtils.isNotBlank(testName) && StringUtils.isNotBlank(result)) {
						resultMap.put(testName, result);
						if (debugMode) System.out.println("Result Map" + resultMap);
					}
				} catch (Exception ex) {
					ex.printStackTrace();
					return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("The system could not extract the test results from LIMS details: " + ex.getMessage());

				}
			}
			// update results and complete the order
			if (debugMode) System.out.println("Result Map " +resultMap+ "For Order ID "+orderId);
			return mapLimsResultsInEmr(orderId, resultMap);
		} else {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("The system could not extract the test results from LIMS details");
		}
	}


	/**
	 *
	 * @param orderId
	 * @param limsResult is a map of test and result.
	 *                   For simple test i.e. Malaria smear, the key is the test name
	 *                   For lab sets i.e. complete blood count, the map entry is a test and the value
	 *                   Results for lab sets are handled as grouped observations with a reference to the parent order.
	 */
	public static ResponseEntity<String> mapLimsResultsInEmr(Integer orderId, Map<String, String> limsResult) {
		debugMode = labsUtils.isLoggingEnabled();
		if (debugMode) System.out.println("Starting mapping ==>");
		if (limsResult == null || limsResult.isEmpty()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("The system encountered empty results from LIMS");
		}
		if (debugMode) System.out.println("Mapping result Map " +limsResult+ "For Order ID "+orderId);
		EncounterType labEncounterType = Context.getEncounterService().getEncounterTypeByUuid(LAB_ENCOUNTER_TYPE_UUID);
		EncounterService encounterService = Context.getEncounterService();
		ConceptService conceptService = Context.getConceptService();
		OrderService orderService = Context.getOrderService();
		ObsService obsService = Context.getObsService();

		Order order = Context.getOrderService().getOrder(orderId);
		Concept orderConcept = order.getConcept();
		if (debugMode) System.out.println("Order Concept ==>"+orderConcept);
		if (orderConcept != null) {
			ObjectNode mapping = readLabTestMappingConfiguration();
			if (mapping == null) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("LIMS-EMR mapping configuration is missing or invalid!");
			}
			// Get mapping for the test concept
			ObjectNode testConceptMapping = (ObjectNode) mapping.get(orderConcept.getUuid());
			if (testConceptMapping == null) {
				if (debugMode) System.out.println("Mapping does not exists ==>");
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("LIMS lab test configuration doesn't support result mapping for test " + orderConcept.getUuid());
			}
			if (debugMode) System.out.println("Mapping exists ==>");
			// setup lab result encounter		
			// Use order type encounter
			Encounter enc = order.getEncounter();
			enc.setEncounterType(labEncounterType);
			enc.setEncounterDatetime(order.getDateCreated());
			enc.setPatient(order.getPatient());
			enc.setCreator(Context.getUserService().getUser(1));

			Obs o = constructObs(order);
			o.setConcept(orderConcept);

			String limsTestName = "", limsTestResult = "";
			
			if (orderConcept.isSet() && orderConcept.getSetMembers().size() > 0) {
				if (debugMode) System.out.println("Test is a set ==>");
				ObjectNode resultSet = (ObjectNode) testConceptMapping.get(LAB_TEST_RESULT_SET_PROPERTY);

				// loop through the results and create an obs group
				//For sets limsTestName is the LOINC Code
				for (Map.Entry<String, String> entry : limsResult.entrySet()) {
					limsTestName = entry.getKey();
					limsTestResult = entry.getValue();


					if (StringUtils.isBlank(limsTestResult) || StringUtils.isBlank(limsTestName)) {
						return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("The system extracted NULL test name or results from LIMS data for lab set " + orderConcept.getUuid());
					}

					if (debugMode) System.out.println("limsTestName ==> "+limsTestName);
					if (debugMode) System.out.println("limsTestResult ==> "+limsTestResult);
					if (debugMode) System.out.println("resultSet ==> "+resultSet);
					if (debugMode) System.out.println("resultSet.get(limsTestName) ==> "+resultSet.get(limsTestName));		


					if (resultSet.get(limsTestName) != null) {
						String memberConceptUuid = resultSet.get(limsTestName).asText();
						Concept memberObsConcept = conceptService.getConceptByUuid(memberConceptUuid);					
						Obs memberObs = constructObs(order);
						if (memberObsConcept != null) {
							memberObs.setConcept(memberObsConcept);
							if (memberObsConcept.getDatatype().isNumeric() || memberObsConcept.getDatatype().isText()) {
								setObsValue(memberObs, memberObsConcept, limsTestResult);
							} else if (memberObsConcept.getDatatype().isCoded()) {
								String memberTestConceptUuid = resultSet.get(limsTestName).asText();
								Concept codedAnswer = Context.getConceptService().getConceptByUuid(memberTestConceptUuid);
								setObsValue(memberObs, memberObsConcept, codedAnswer);
							}
						}

						if (debugMode) System.out.println("Member set uuids ==>"+memberConceptUuid);  // prints each UUID					
						if (debugMode) System.out.println("memberObsConcept ==>"+memberObsConcept);
						if (debugMode) System.out.println("memberConceptUuid ==>"+memberConceptUuid);
						if (debugMode) System.out.println("memberObs ==>"+memberObs);   
						
						if (memberObsConcept != null) {

							memberObs.setConcept(memberObsConcept);
							if (debugMode) System.out.println("memberObs setConcept==>"+memberObs);
							if (memberObsConcept.getDatatype().isNumeric() || memberObsConcept.getDatatype().isText()) {
								setObsValue(memberObs, memberObsConcept, limsTestResult);
								if (debugMode) System.out.println("memberObs setValue==>"+memberObs);
								if (debugMode) System.out.println("memberObs setConcept==>"+memberObsConcept);
								if (debugMode) System.out.println("memberObs setList result==>"+limsTestResult);
							} else if (memberObsConcept.getDatatype().isCoded()) {
								String memberTestConceptUuid = resultSet.get(limsTestName).asText();
								Concept codedAnswer = Context.getConceptService().getConceptByUuid(memberTestConceptUuid);
								setObsValue(memberObs, memberObsConcept, codedAnswer);
							}
							if (debugMode) System.out.println("Saving results ==>");
						}

						//obsService.saveObs(memberObs, null);
						memberObs.setObsGroup(o);
						memberObs.setEncounter(enc);
						//memberObs.setConcept(orderConcept);
						if (debugMode) System.out.println("memberObs set everything==>"+memberObs);
						if (debugMode) System.out.println("orderConcept==>"+orderConcept);
						o.addGroupMember(memberObs);
						if (debugMode) System.out.println("o everything==>"+o);
					}
				}

			} else { // this is for a non-set test.
				for (Map.Entry<String, String> entry : limsResult.entrySet()) {
					limsTestName = entry.getKey();
					limsTestResult = entry.getValue();
				}

				if (StringUtils.isBlank(limsTestResult) || StringUtils.isBlank(limsTestName)) {
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("The system extracted NULL test name or results from LIMS data for lab test " + orderConcept.getUuid());
				}

				else if (orderConcept.getDatatype().isNumeric()) {
					try {
						Double.parseDouble(limsTestResult);
						setObsValue(o, orderConcept, limsTestResult);
					} catch (NumberFormatException e) {
						if (debugMode) System.out.println("Lims Results format exception "+e.getMessage());
						setObsValue(o, orderConcept, 0.0);						
					}
					
				}else if (orderConcept.getDatatype().isText()) {
						setObsValue(o, orderConcept, limsTestResult);
				} else if (orderConcept.getDatatype().isCoded()) {
                   	ObjectNode resultSet = (ObjectNode) testConceptMapping.get(LAB_TEST_RESULT_SET_PROPERTY);
					String codedAnswerConceptUuid = resultSet.get(limsTestResult).asText();
					Concept codedAnswer = Context.getConceptService().getConceptByUuid(codedAnswerConceptUuid);
					setObsValue(o, orderConcept, codedAnswer);
				}
			}
			try {
				o.setEncounter(enc);
				o.setConcept(orderConcept);
				enc.addObs(o);
				encounterService.saveEncounter(enc);
				orderService.discontinueOrder(order, "Results received", new Date(), order.getOrderer(), enc);
				order.setFulfillerStatus(Order.FulfillerStatus.COMPLETED);

				//update lims queue
				KenyaemrOrdersService kenyaemrOrdersService = Context.getService(KenyaemrOrdersService.class);
				LimsQueue limsQueue = kenyaemrOrdersService.getLimsQueueByOrder(order);
				if (limsQueue != null) {
					limsQueue.setStatus(LimsQueueStatus.COMPLETED);
					limsQueue.setDateLastChecked(new Date());
					kenyaemrOrdersService.saveLimsQueue(limsQueue);
				}
				return ResponseEntity.status(HttpStatus.OK).body("Lab results updated successfully");
				

			} catch (Exception e) {
				if (debugMode) System.out.println(e.getMessage());
				e.printStackTrace();
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An error was encountered while updating results for " + order.getConcept().getUuid() + ". Error: " + e.getMessage());
			}
		} else {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Could not find concept for test " + order.getConcept().getUuid());
		}
	}

	/**
	 * Set numeric or text obs values
	 *
	 * @param obs
	 * @param concept
	 * @param obsValue
	 */
//	private static void setObsValue(Obs obs, Concept concept, String obsValue) {
//		if (concept.isNumeric()) {
//			obs.setValueNumeric(Double.valueOf(obsValue));
//		} else if (concept.getDatatype().isText()) {
//			obs.setValueText(obsValue);
//		}
//	}
	private static void setObsValue(Obs obs, Concept concept, Object rawValue) {

		if (obs == null || concept == null || rawValue == null) {
			return;
		}

		ConceptDatatype dt = concept.getDatatype();
		String value = rawValue.toString().trim();

		// Handle LIMS null-like values
		if (value.isEmpty() ||
			value.equalsIgnoreCase("NULL") ||
			value.equalsIgnoreCase("NA") ||
			value.equalsIgnoreCase("-")) {
			return;
		}

		try {

			// =========================
			// NUMERIC
			// =========================
			if (dt.isNumeric()) {
				try {
					Double.parseDouble(value);
				} catch (NumberFormatException e) {
					if (debugMode) System.out.println("Results format exception "+e.getMessage());
					return;
				}

				double numericValue = 0.0;

				try {
					if (debugMode) System.out.println("Checking value is numeric first " + value);
					numericValue = Double.parseDouble(value);

					if (!Double.isFinite(numericValue)) {
						if (debugMode) System.out.println("Value is not finite: " + value);
						return;
					}

				} catch (NumberFormatException e) {
					if (debugMode) System.out.println("Value is not numeric: " + e.getMessage());
					numericValue = 0.0; // assign default if string
				}

// 🔎 Check reference range
				ConceptNumeric numericConcept = Context.getConceptService()
					.getConceptNumeric(concept.getConceptId());

				if (numericConcept != null) {

					Double hi = numericConcept.getHiAbsolute();
					Double low = numericConcept.getLowAbsolute();

					if (hi != null && numericValue > hi) {
						numericValue = hi;   // clamp to upper bound
					}

					if (low != null && numericValue < low) {
						numericValue = low;  // clamp to lower bound
					}
				}

// safe to use numeric value
				if (Double.isFinite(numericValue)) {
					obs.setValueNumeric(numericValue);
				}
			}

			// =========================
			// TEXT
			// =========================
			else if (dt.isText()) {
				obs.setValueText(value);
			}

			// =========================
			// CODED
			// =========================
			else if (dt.isCoded()) {

				Concept answerConcept = null;

				if (rawValue instanceof Concept) {
					answerConcept = (Concept) rawValue;
				} else {
					answerConcept = Context.getConceptService().getConceptByUuid(value);
				}

				if (answerConcept != null) {
					obs.setValueCoded(answerConcept);
				}
			}

			// =========================
			// BOOLEAN
			// =========================
			else if (dt.isBoolean()) {
				obs.setValueBoolean(
					value.equalsIgnoreCase("true") ||
						value.equalsIgnoreCase("yes") ||
						value.equals("1")
				);
			}

		} catch (Exception e) {
			if (debugMode) System.out.println("Failed to set obs value [" + value + "] for concept " + concept.getUuid());
		}
	}

	/**
	 * Set coded answer
	 *
	 * @param obs
	 * @param concept
	 * @param obsValue
	 */
	private static void setObsValue(Obs obs, Concept concept, Concept obsValue) {
		if (obs == null || concept == null || obsValue == null) {
			return;
		}
		if (concept.getDatatype().isCoded()) {
			obs.setValueCoded(obsValue);
		}
	}

	/**
	 * Creates an obs stub from order details
	 *
	 * @param order
	 * @return
	 */
	private static Obs constructObs(Order order) {
		Obs o = new Obs();
		o.setDateCreated(new Date());
		o.setCreator(Context.getUserService().getUser(1));
		o.setObsDatetime(order.getDateActivated());
		o.setPerson(order.getPatient());
		o.setOrder(order);
		o.setLocation(Utils.getDefaultLocation());
		if (debugMode) System.out.println("Obs stub created ==>"+o);
		return o;
	}
	/**
	 * SKIP stale lims submissions
	 * Submissions > 3 days without results
	 * @param limsQueue
	 * @return
	 */
	private void skipIfStale(LimsQueue limsQueue) {
		KenyaemrOrdersService kenyaemrOrdersService = Context.getService(KenyaemrOrdersService.class);
		Date dateOrderCreated = limsQueue.getDateCreated();
		if (dateOrderCreated != null) {
			long diffInMillis = new Date().getTime() - dateOrderCreated.getTime();
			long diffInDays = TimeUnit.MILLISECONDS.toDays(diffInMillis);
			if (diffInDays > 2) {
				limsQueue.setDateLastChecked(new Date());
				limsQueue.setStatus(LimsQueueStatus.SKIPPED);
				kenyaemrOrdersService.saveLimsQueue(limsQueue);
			}
		}
	}
}
