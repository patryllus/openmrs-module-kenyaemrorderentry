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

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * A class for mapping lab tests and results between a labware facility-wide implementation and the EMR
 * TODO: abstract the key methods into a parent class and have various lab systems implement the specifics
 */
public class LabwareFacilityWideResultsMapper {
	public static final String LAB_TEST_RESULT_SET_PROPERTY = "result";
	public static final String LAB_RESULT_CODED_ANSWER_SET_PROPERTY = "codedAnswer";
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
			Map<String, List<String>> resultMap = new HashMap<>();
			JsonArray resultArray = resultsObj.get("data").getAsJsonArray();
			Integer orderId = null;
			String valueCode = "";
			for (int i = 0; i < resultArray.size(); i++) {
				try {
					JsonObject o = resultArray.get(i).getAsJsonObject();
					orderId = o.get("identifier").getAsInt();
					String testName = !o.isJsonNull() && !o.get("componentCode").isJsonNull() ? o.get("componentCode").getAsString() : "";
					String result = !o.isJsonNull() && !o.get("value").isJsonNull() ? o.get("value").getAsString() : "";
					valueCode = !o.isJsonNull() && !o.get("valueCode").isJsonNull() ? o.get("valueCode").getAsString() : "";
					if (StringUtils.isNotBlank(testName) && StringUtils.isNotBlank(result)) {
						resultMap.put(testName, Arrays.asList(result, valueCode));
						if (debugMode) System.out.println("Result Map" + resultMap);
					}
				} catch (Exception ex) {
					ex.printStackTrace();
					return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("The system could not extract the test results from LIMS details: " + ex.getMessage());

				}
			}
			// update results and complete the order
			if (debugMode) System.out.println("Result Map " + resultMap + "For Order ID " + orderId);
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
	public static ResponseEntity<String> mapLimsResultsInEmr(Integer orderId, Map<String, List<String>> limsResult) {
		debugMode = labsUtils.isLoggingEnabled();
		if (debugMode) System.out.println("Starting mapping ==>");
		if (limsResult == null || limsResult.isEmpty()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("The system encountered empty results from LIMS");
		}
		if (debugMode) System.out.println("Mapping result Map " + limsResult + "For Order ID " + orderId);
		EncounterType labEncounterType = Context.getEncounterService().getEncounterTypeByUuid(LAB_ENCOUNTER_TYPE_UUID);
		EncounterService encounterService = Context.getEncounterService();
		ConceptService conceptService = Context.getConceptService();
		OrderService orderService = Context.getOrderService();
		ObsService obsService = Context.getObsService();

		Order order = Context.getOrderService().getOrder(orderId);
		Concept orderConcept = order.getConcept();
		if (debugMode) System.out.println("Mapping Order Concept ==>" + orderConcept);
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
			List<String> limsCombinedTestResult = new ArrayList<>();
			String valueCode = null;

			if (orderConcept.isSet() && orderConcept.getSetMembers().size() > 0) {
				if (debugMode) System.out.println("Test is a set ==>");
				ObjectNode resultSet = (ObjectNode) testConceptMapping.get(LAB_TEST_RESULT_SET_PROPERTY);
				ObjectNode codedAnswerSet = (ObjectNode) testConceptMapping.get(LAB_RESULT_CODED_ANSWER_SET_PROPERTY);

				// loop through the results and create an obs group
				//For sets limsTestName is the LOINC Code
				//if (debugMode) System.out.println("resultSet ==> " + resultSet);
				//if (debugMode) System.out.println("codedAnswerSet ==> " + codedAnswerSet);
				//if (debugMode) System.out.println("Results Payload ==> " + limsResult);
				int added = 0;
				int skippedNoConcept = 0;
				int skippedNoValue = 0;
				for (Map.Entry<String, List<String>> entry : limsResult.entrySet()) {
					limsTestName = entry.getKey();
					limsCombinedTestResult = entry.getValue();

					if (limsCombinedTestResult != null && limsCombinedTestResult.size() >= 2) {
						limsTestResult = limsCombinedTestResult.get(0);
						valueCode = limsCombinedTestResult.get(1);
					}

					if (debugMode)
						System.out.println("Results value + Value code for each results ==> " + limsCombinedTestResult);
					if (debugMode) System.out.println("Results value (limsTestResult) ==>" + limsTestResult);
					if (debugMode) System.out.println("Component value (limsTestName)==> " + limsTestName);
					//if (debugMode) System.out.println("Loinc code + uuid of results (key - pair) ==> " + resultSet);
					if (debugMode)System.out.println("Uuid of test within a set: resultSet.get(limsTestName) ==> " + resultSet.get(limsTestName));
					if (debugMode) System.out.println("Value code ==> " + valueCode);
					if (debugMode) System.out.println("Starting to map Value code ==> ");

					String memberConceptUuid = "";
					String codedAnswerConceptUuid = "";
					try {
						 codedAnswerConceptUuid = codedAnswerSet.get(valueCode) != null
							? codedAnswerSet.get(valueCode).asText()
							: "";			

					} catch (Exception e) {
						if (debugMode) System.out.println("codedAnswerConceptUuid not available ==> ");
						//e.printStackTrace(); 
					}
					try {
						 memberConceptUuid = resultSet.get(limsTestName) != null
							? resultSet.get(limsTestName).asText()
							: "";

					} catch (Exception e) {
						if (debugMode) System.out.println("memberConceptUuid not available ==> ");
						//e.printStackTrace();  
					}
									
					
					
					if (debugMode) System.out.println("Starting to memberConceptUuid ==> ");
					if (debugMode) System.out.println("Starting to memberConceptUuid ==> "+ memberConceptUuid);
					if (debugMode) System.out.println("Starting to codedAnswerConceptUuid ==> "+ codedAnswerConceptUuid);

					Obs memberObs = constructObs(order);
					if (debugMode) System.out.println("Starting to process ==> ");
					if (StringUtils.isNotBlank(memberConceptUuid)) {
						Concept memberObsConcept = conceptService.getConceptByUuid(memberConceptUuid);
						if (memberObsConcept == null) {
							System.out.println("Skipping memberObs: invalid concept UUID = " + memberConceptUuid);
							continue;
						}
						if (debugMode) System.out.println("checking memberObsConcept ==> " + memberObsConcept);
						//For component results object node on results mappings
						if (debugMode) System.out.println("Using results object on mapper ==> " + memberObsConcept);
						if (memberObsConcept.getDatatype().isNumeric() || memberObsConcept.getDatatype().isText()) {
							memberObs.setConcept(memberObsConcept);
							setObsValue(memberObs, memberObsConcept, limsTestResult);
						} else if (memberObsConcept.getDatatype().isCoded() && StringUtils.isNotBlank(codedAnswerConceptUuid)) {
							if (debugMode) System.out.println("checking codedAnswerConceptUuid ==> " + codedAnswerConceptUuid);
							Concept codedAnswerObsConcept =
								conceptService.getConceptByUuid(codedAnswerConceptUuid);
							memberObs.setConcept(memberObsConcept);

							
							if (debugMode) System.out.println("memberObs ==>"+memberObs);
							if (debugMode) System.out.println("memberObsConcept ==>"+memberObsConcept);
							if (debugMode) System.out.println("codedAnswerConceptUuid ==>"+codedAnswerConceptUuid);
							//setObsValue(memberObs, memberObsConcept, codedAnswerConceptUuid);

							//Concept answerConcept = conceptService.getConceptByUuid(codedAnswerConceptUuid);
							if (codedAnswerObsConcept != null) {
								memberObs.setValueCoded(codedAnswerObsConcept);
							} else {
								System.out.println("Missing coded answer concept: "
									+ codedAnswerConceptUuid);
								continue;
							}

							if (debugMode) System.out.println("Setting value obs ==>");
							System.out.println("After setObsValue:");
							System.out.println("ValueText=" + memberObs.getValueText());
							System.out.println("ValueNumeric=" + memberObs.getValueNumeric());
							System.out.println("ValueCoded=" + memberObs.getValueCoded());
							if (debugMode) System.out.println("Set value obs ==>");
						}
						if (debugMode) System.out.println("Saving  all results ==>");
					}					

					if (debugMode) System.out.println("Saving results ==>");
					//obsService.saveObs(memberObs, null);
					memberObs.setObsGroup(o);
					memberObs.setEncounter(enc);
					//memberObs.setConcept(orderConcept);
					if (debugMode) System.out.println("memberObs set everything==>" + memberObs);
					if (debugMode) System.out.println("orderConcept==>" + orderConcept);

					if (debugMode) System.out.println("Adding group members value obs ==>");
					System.out.println("memberObs concept = " + memberObs.getConcept());
					System.out.println("memberObs value coded = " + memberObs.getValueCoded());
					System.out.println("memberObs value text = " + memberObs.getValueText());
					System.out.println("memberObs value numeric = " + memberObs.getValueNumeric());

					if (debugMode) System.out.println("Added group members value obs ==>");
					
					//Skipping invalid Obs (no value)				

					boolean hasValue =
						memberObs.getValueText() != null ||
							memberObs.getValueNumeric() != null ||
							memberObs.getValueCoded() != null ||
							memberObs.getValueBoolean() != null ||
							memberObs.getValueDatetime() != null;

					if (memberObs.getConcept() == null) {
						skippedNoConcept++;
						System.out.println("SKIPPING: Concept is NULL");
					}
					else if (!hasValue) {
						skippedNoValue++;
						System.out.println("SKIPPING: No value for concept = "
							+ memberObs.getConcept().getUuid());
					}
					else {
						if (debugMode) System.out.println("Adding valid Obs: "
							+ memberObs.getConcept().getUuid());
						added++;

						o.addGroupMember(memberObs);
					}
					if (debugMode) System.out.println("o everything==>" + o);

				}
				System.out.println("===== OBS GROUP SUMMARY =====");
				System.out.println("Added members: " + added);
				System.out.println("Skipped (no concept): " + skippedNoConcept);
				System.out.println("Skipped (no value): " + skippedNoValue);

			} else { // this is for a non-set test.
				for (Map.Entry<String, List<String>> entry : limsResult.entrySet()) {
					limsTestName = entry.getKey();
					limsCombinedTestResult = entry.getValue();
				}
				if (limsCombinedTestResult != null && limsCombinedTestResult.size() >= 2) {
					limsTestResult = limsCombinedTestResult.get(0);
					valueCode = limsCombinedTestResult.get(1);
				}
				if (debugMode) System.out.println("limsTests result non set ==>" + limsTestResult);
				if (debugMode) System.out.println("Value coded non set==>" + valueCode);
				if (StringUtils.isBlank(limsTestResult) || StringUtils.isBlank(limsTestName)) {

				} else if (orderConcept.getDatatype().isNumeric()) {
					try {
						Double.parseDouble(limsTestResult);
						setObsValue(o, orderConcept, limsTestResult);
					} catch (NumberFormatException e) {
						if (debugMode) System.out.println("Lims Results format exception " + e.getMessage());
						setObsValue(o, orderConcept, 0.0);
					}

				} else if (orderConcept.getDatatype().isText()) {
					setObsValue(o, orderConcept, limsTestResult);
				} else if (orderConcept.getDatatype().isCoded()) {
					ObjectNode resultSet = (ObjectNode) testConceptMapping.get(LAB_TEST_RESULT_SET_PROPERTY);
					ObjectNode codedAnswerSet = (ObjectNode) testConceptMapping.get(LAB_RESULT_CODED_ANSWER_SET_PROPERTY);
					if (codedAnswerSet == null) {
						if (debugMode) System.out.println("Coded result set does not exists ==>");
					}
					String codedAnswerConceptUuid = resultSet.get(limsTestResult).asText();
					String codedAnswerUuid = codedAnswerSet.get(valueCode).asText().replace("\"", "");
					setObsValue(o, orderConcept, codedAnswerUuid);
				}
			}
			try {
//				System.out.println("Saving encounter: " + o.getObsId());
//				o.setEncounter(enc);
//				o.setConcept(orderConcept);
//				enc.addObs(o);
//				encounterService.saveEncounter(enc);
				try {
					System.out.println("Saving encounter: " + o.getObsId());

					o.setEncounter(enc);
					o.setConcept(orderConcept);

					enc.addObs(o);

					// DEBUG: Find member obs with no value
					if (o.getGroupMembers() != null) {
						
						for (Obs member : o.getGroupMembers()) {

							boolean hasValue =
								member.getValueText() != null ||
									member.getValueNumeric() != null ||
									member.getValueCoded() != null ||
									member.getValueDatetime() != null ||
									member.getValueBoolean() != null;

							if (!hasValue) {

								System.out.println("===== OFFENDING OBS =====");

								// Obs identity
								System.out.println("Obs ID: " + member.getObsId());
								System.out.println("UUID: " + member.getUuid());

								// Concept (safe)
								Concept concept = member.getConcept();
								if (concept == null) {
									System.out.println("Concept: NULL");
								} else {
									System.out.println("Concept ID: " + concept.getConceptId());
									System.out.println("Concept UUID: " + concept.getUuid());

									if (concept.getName() != null) {
										System.out.println("Concept Name: " + concept.getName().getName());
									}

									if (concept.getDatatype() != null) {
										System.out.println("Concept Datatype: " + concept.getDatatype().getName());
									}
								}

								// Values (dump all)
								System.out.println("ValueText: " + member.getValueText());
								System.out.println("ValueNumeric: " + member.getValueNumeric());
								System.out.println("ValueCoded: " + member.getValueCoded());
								System.out.println("ValueDatetime: " + member.getValueDatetime());
								System.out.println("ValueBoolean: " + member.getValueBoolean());

								// Extra context (VERY useful for tracing)
								System.out.println("Order Concept (parent): " + o.getConcept());
								System.out.println("Parent Obs UUID: " + o.getUuid());

								System.out.println("=========================");
							}
						}
					}

					System.out.println("===== FINAL GROUP VALIDATION CHECK =====");
				
					for (Obs member : o.getGroupMembers()) {

						boolean hasValue =
							member.getValueText() != null ||
								member.getValueNumeric() != null ||
								member.getValueCoded() != null ||
								member.getValueDatetime() != null ||
								member.getValueBoolean() != null;

						if (!hasValue) {
							System.out.println("INVALID MEMBER FOUND");

							System.out.println("Concept: " + (member.getConcept() != null
								? member.getConcept().getUuid()
								: "NULL"));

							System.out.println("Datatype: " + (member.getConcept() != null
								? member.getConcept().getDatatype().getName()
								: "NULL"));
						}
					}

					try {
						encounterService.saveEncounter(enc);
					}
					catch (ValidationException e) {
						System.out.println("Validation failed:");

						if (e.getErrors() != null) {
							e.getErrors().getAllErrors().forEach(err ->
								System.out.println(err));
						}

						throw e;
					}

				} catch (Exception e) {
					e.printStackTrace();
				}

				if (debugMode) {
					System.out.println("Parent Obs ID after save: " + o.getObsId());

					Obs savedObs = Context.getObsService().getObs(o.getObsId());
					System.out.println("Saved group members: "
						+ (savedObs.getGroupMembers() != null
						? savedObs.getGroupMembers().size()
						: 0));
				}
				
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
	 * @param obs
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
		if (debugMode) System.out.println("Value here ==> " + value);

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
				String numericResult = value.replaceAll("[^0-9.]", "");
				try {
					
					Double.parseDouble(numericResult);
				} catch (NumberFormatException e) {
					if (debugMode) System.out.println("Results format exception " + e.getMessage());
					return;
				}

				double numericValue = 0.0;

				try {
					if (debugMode) System.out.println("Checking value is numeric first " + numericResult);
					numericValue = Double.parseDouble(numericResult);

					if (!Double.isFinite(numericValue)) {
						if (debugMode) System.out.println("Value is not finite: " + numericResult);
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
				if (debugMode) System.out.println("Coded result here  value==> " + value);
				if (debugMode) System.out.println("Coded result here rawValue==> " + rawValue);
				Concept answerConcept = null;

				if (rawValue instanceof Concept) {
					answerConcept = (Concept) rawValue;
					if (debugMode) System.out.println("Processing rawValue concept uuid ==> " + rawValue);
				} else {
					if (debugMode) System.out.println("Processing value answer concept uuid==> " + value);
					answerConcept = Context.getConceptService().getConceptByUuid(value);
					if (debugMode) System.out.println("Processing answerConcept==> " + answerConcept);
				}

				if (answerConcept != null) {
					if (debugMode) System.out.println("Saving ==> " + answerConcept);
					obs.setValueCoded(answerConcept);
				}

				if (debugMode) System.out.println("Post Saving ==> " + answerConcept);
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
			if (debugMode)
				System.out.println("Failed to set obs value [" + value + "] for concept " + concept.getUuid());
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
		if (debugMode) System.out.println("Obs stub created ==>" + o);
		return o;
	}

	static String drugNameConverter(String key) {
		ConceptService conceptService = Context.getConceptService();
		Map<Concept, String> drugNameList = new HashMap<Concept, String>();
		drugNameList.put(conceptService.getConcept(1652), "af1a");
		drugNameList.put(conceptService.getConcept(1652), "cf1a");
		drugNameList.put(conceptService.getConcept(164505), "tle");
		drugNameList.put(conceptService.getConcept(105281), "ctx");
		return drugNameList.get(key);
	}

}
