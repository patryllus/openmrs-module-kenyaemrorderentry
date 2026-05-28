/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 * <p>
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.kenyaemrorderentry.labDataExchange;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.joda.time.DateTime;
import org.joda.time.Months;
import org.joda.time.Weeks;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.openmrs.ConditionVerificationStatus;
import org.openmrs.Diagnosis;
import org.openmrs.GlobalProperty;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifierType;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.api.DiagnosisService;
import org.openmrs.api.context.Context;
import org.openmrs.module.kenyaemrorderentry.ModuleConstants;
import org.openmrs.module.kenyaemrorderentry.task.PushLabRequestsTask;
import org.openmrs.module.kenyaemrorderentry.util.Utils;
import org.openmrs.module.metadatadeploy.MetadataUtils;
import org.openmrs.module.reporting.common.Age;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.openmrs.module.kenyaemrorderentry.labDataExchange.LabwareFacilityWideResultsMapper.readLabTestMappingConfiguration;


public class LimsSystemWebRequest {

    public static final String LAB_TEST_CODE_PROPERTY = "testCode";
	public static final String OPENMRS_ID = "dfacd928-0370-4315-99d7-6ec1c9f7ae76";
    private static final Logger log = LoggerFactory.getLogger(PushLabRequestsTask.class);
	private static Boolean debugMode = false;

    /**
     * Generates the order payload used to post to Lims server
     *
     * @param order
     * @return
     */
    public static JSONObject generateLIMSpostPayload(Order order) {
		debugMode = labsUtils.isLoggingEnabled();
        SimpleDateFormat sd = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        JSONObject payload = new JSONObject();

        // Get mapping for the test concept
        ObjectNode mapping = readLabTestMappingConfiguration();
        if (mapping == null) {
			if (debugMode) System.out.println("LIMS-EMR mapping configuration is missing or invalid!");
            return payload;
        }
        ObjectNode testConceptMapping = (ObjectNode) mapping.get(order.getConcept().getUuid());
        if (testConceptMapping == null) {
			if (debugMode) System.out.println("LIMS-EMR mapping: No mapping found for order concept: " + order.getConcept().getId());
            return payload;
        }

        String labTestId = null;

        // assign labTestId as test code from mapper. We want to fail early if there is no mapping for the order
        labTestId = testConceptMapping.has(LAB_TEST_CODE_PROPERTY)? testConceptMapping.get(LAB_TEST_CODE_PROPERTY).asText() : null;
        if (StringUtils.isBlank(labTestId)) {
			if (debugMode) System.out.println("LIMS-EMR mapping: Test code not found for the order concept: " + order.getConcept().getId());
            return payload;
        }
		PatientIdentifierType openmrsIdType = MetadataUtils.existing(PatientIdentifierType.class, OPENMRS_ID);		
        Patient patient = order.getPatient();
        String patientId = patient.getPatientId() != null ? patient.getPatientId().toString() : "";
        String openmrsId =  patient.getPatientIdentifier(openmrsIdType) != null ? patient.getPatientIdentifier(openmrsIdType).getIdentifier() : "";
        String address = null;
        String ward = null;
        String village = null;
        String landmark = null;
        String firstName = null;
        String middleName = null;
        String lastName = null;
        String gender = patient.getGender();
        //Test details
        String testName = null;
        String dateRequestReceived = null;
        String requestedByName = null;

        //Diagnosis
        String diagnosisName = null;
        String labRequestId = null;
        Person person = Context.getPersonService().getPerson(patient.getPatientId());
        //Address
        if (person.getPersonAddress() != null) {
            village = person.getPersonAddress().getCityVillage() != null ? person.getPersonAddress().getCityVillage() : "";
            landmark = person.getPersonAddress().getAddress2() != null ? person.getPersonAddress().getAddress2() : "";
            address = person.getPersonAddress().getAddress1() != null ? person.getPersonAddress().getAddress1() : "";
            ward = person.getPersonAddress().getAddress4() != null ? person.getPersonAddress().getAddress4() : "";

        }
        //Names
        PersonName personName = person.getPersonName();
        if (personName != null) {
            firstName = personName.getGivenName() != null ? personName.getGivenName() : "";
            middleName = personName.getMiddleName() != null ? personName.getMiddleName() : "";
            lastName = personName.getFamilyName() != null ? personName.getFamilyName() : "";
        }

        //Age and patientAgeUnit
		String dob = patient.getBirthdate() != null ? sd.format(patient.getBirthdate()) : null;
		Age age = patient.getBirthdate() != null ? new Age(patient.getBirthdate()) : null;

		Date today = new Date();
		Integer ageInWeeks = getAgeInWeeks(person.getBirthdate(), today);
		Integer ageInMonths = getAgeInMonths(person.getBirthdate(), today);

// Construct age value + unit
		Integer patientAge = null;
		String patientAgeUnit = null;

		if (age != null) {

			if (age.getFullYears() >= 1) {
				patientAge = age.getFullYears();
				patientAgeUnit = "YEARS";

			} else if (age.getFullMonths() >= 1) {
				patientAge = ageInMonths;
				patientAgeUnit = "MONTHS";

			} else {
				patientAge = ageInWeeks;
				patientAgeUnit = "WEEKS";
			}
		}

        //Gets final and preliminary diagnosis
        DiagnosisService diagnosisService = Context.getDiagnosisService();
        List<Diagnosis> allDiagnosis = diagnosisService.getDiagnosesByEncounter(order.getEncounter(), false, false);
        if (!allDiagnosis.isEmpty()) {
            for (Diagnosis diagnosisType : allDiagnosis) {
                if (diagnosisType.getCertainty().equals(ConditionVerificationStatus.PROVISIONAL)) {
                    diagnosisName = diagnosisType.getDiagnosis().getCoded().getName().getName();
                } else {
                    diagnosisName = diagnosisType.getDiagnosis().getCoded().getName().getName();
                }
            }
        }
        //Order details
        dateRequestReceived = sd.format(order.getDateCreated());
        requestedByName = order.getCreator().getGivenName() != null ? order.getCreator().getGivenName() : "";
        labRequestId = order.getOrderId().toString();
        testName = order.getConcept().getName().getName();
		//Add performer and requester facility MFL
		String facilityCode = Utils.getDefaultLocationMflCode(Utils.getDefaultLocation());
		
		
        Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);

        //Create LIMS order payload

        payload.put("patientAddress", address + "" + village + "" + landmark);
        payload.put("authoredOn", dateRequestReceived);
        payload.put("reasonCode", diagnosisName != null ? diagnosisName : "No Diagnosis");
        payload.put("priority", "routine");
        payload.put("identifier", labRequestId);
        //Test Object
        JSONObject testsObj = new JSONObject();
        JSONArray labTest = new JSONArray();
        testsObj.put("identifier", labTestId);
        testsObj.put("text", testName);
        labTest.add(testsObj);
        payload.put("code", labTest);

        payload.put("locationReference", ward);
		payload.put("subjectIdentifier", openmrsId);
        payload.put("patientAge", patientAge);
        payload.put("patientAgeUnit", patientAgeUnit);
        payload.put("patientBed", "");
        payload.put("patientBirthDate", dob);
        payload.put("patientGivenName", firstName);
        payload.put("patientGender", gender != null ? labsUtils.formatGender(gender) : null);
        payload.put("subject", patientId);        
        payload.put("patientMiddleName", middleName);
        payload.put("patientTelecom", patient.getAttribute("Telephone contact") != null ? patient.getAttribute("Telephone contact").getValue() : "");
        payload.put("encounterClass", "OUTPATIENT");
        payload.put("patientFamilyName", lastName);
        payload.put("patientWard", "");
        payload.put("requisition", "");
		payload.put("requisitionType", "PHYSICAL");
        payload.put("requester", requestedByName);
        payload.put("requesterMFL", facilityCode);
        payload.put("performerMFL", facilityCode);

		if (debugMode) System.out.println("Payload generated for orderId : " +labRequestId+ "to send to Lims");

        return payload;

    }

    public static boolean postLabOrderRequestToLims(String params) throws IOException {
		debugMode = labsUtils.isLoggingEnabled();
        String serverUrl = "";
        String API_KEY = "";
        GlobalProperty gpLIMsServerPushUrl = Context.getAdministrationService().getGlobalPropertyObject(ModuleConstants.GP_LIMS_LAB_SERVER_REQUEST_URL);
        GlobalProperty gpLIMsApiToken = Context.getAdministrationService().getGlobalPropertyObject(ModuleConstants.GP_LIMS_LAB_SERVER_API_TOKEN);
        serverUrl = gpLIMsServerPushUrl.getPropertyValue().trim();
        API_KEY = gpLIMsApiToken.getPropertyValue().trim();
        SSLConnectionSocketFactory sslsf = null;
        GlobalProperty gpSslVerification = Context.getAdministrationService().getGlobalPropertyObject(ModuleConstants.GP_SSL_VERIFICATION_ENABLED);		

		if (gpSslVerification != null) {
			String sslVerificationEnabled = gpSslVerification.getPropertyValue();

			if (StringUtils.isNotBlank(sslVerificationEnabled)) {
				if (sslVerificationEnabled.equalsIgnoreCase("true")) {
					sslsf = Utils.sslConnectionSocketFactoryDefault();
				} else {
					sslsf = Utils.sslConnectionSocketFactoryWithDisabledSSLVerification();
				}
			}
		}

		boolean success = false;

		try (CloseableHttpClient httpClient =
				 (sslsf != null)
					 ? HttpClients.custom().setSSLSocketFactory(sslsf).build()
					 : HttpClients.createDefault()) {

			String payload = params;

			if (debugMode) System.out.println("LIMS Lab Results POST: Server URL: " + serverUrl);
			if (debugMode) System.out.println("LIMS Lab Request POST: Server Payload: " + payload);

			HttpPost postRequest = new HttpPost(serverUrl);
			postRequest.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
			postRequest.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);

			postRequest.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));

			try (CloseableHttpResponse response = httpClient.execute(postRequest)) {

				int statusCode = response.getStatusLine().getStatusCode();
				String responseBody = response.getEntity() != null
					? EntityUtils.toString(response.getEntity())
					: "";

				if (debugMode) System.out.println("Labware status code ==> " + statusCode);
				if (debugMode) System.out.println("Labware response ==> " + responseBody);

				if (statusCode >= 200 && statusCode < 300) {
					if (debugMode) System.out.println("LIMS Lab Request POST: Successfully pushed a lab test");
					Context.flushSession();
					success = true;

				} else if (statusCode == 400) {
					if (debugMode) System.out.println("Bad Request (Missing fields): " + responseBody);

				} else if (statusCode == 401) {
					if (debugMode) System.out.println("Unauthorized: Check API key");

				} else if (statusCode >= 500) {
					if (debugMode) System.out.println("Server error: " + responseBody);

				} else {
					if (debugMode) System.out.println("Unexpected status code: " + statusCode + " Body: " + responseBody);
				}
			}

		} catch (Exception e) {
			if (debugMode) System.out.println("LIMS Lab Request POST: Could not push requests to the lab!"+e);
		}

		return success;

	}

    public static void pullFacilityWideLimsLabResult(List<Integer> orderIds) throws IOException {
		System.out.println("Pull Facility Wide Results and persist");
        String serverUrl = "";
        String API_KEY = "";
        GlobalProperty gpLIMsServerPushUrl = Context.getAdministrationService().getGlobalPropertyObject(ModuleConstants.GP_LIMS_LAB_SERVER_RESULT_URL);
        GlobalProperty gpLIMsApiToken = Context.getAdministrationService().getGlobalPropertyObject(ModuleConstants.GP_LIMS_LAB_SERVER_API_TOKEN);
        serverUrl = gpLIMsServerPushUrl.getPropertyValue().trim();
        API_KEY = gpLIMsApiToken.getPropertyValue().trim();
        SSLConnectionSocketFactory sslsf = null;
        GlobalProperty gpSslVerification = Context.getAdministrationService().getGlobalPropertyObject(ModuleConstants.GP_SSL_VERIFICATION_ENABLED);

        if (gpSslVerification != null) {
            String sslVerificationEnabled = gpSslVerification.getPropertyValue();
            if (StringUtils.isNotBlank(sslVerificationEnabled)) {
                if (sslVerificationEnabled.equals("true")) {
                    sslsf = Utils.sslConnectionSocketFactoryDefault();
                } else {
                    sslsf = Utils.sslConnectionSocketFactoryWithDisabledSSLVerification();
                }
            }
        }

        CloseableHttpClient httpClient = HttpClients.custom().setSSLSocketFactory(sslsf).build();

		for (Integer order : orderIds) {
			try {
				URIBuilder builder = new URIBuilder(serverUrl);
				builder.addParameter("LabRequestId", order.toString());
				URI uri = builder.build();

				HttpGet httpget = new HttpGet(uri);
				httpget.setHeader(HttpHeaders.ACCEPT, "application/json");
				httpget.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);

				try (CloseableHttpResponse response = httpClient.execute(httpget)) {

					int statusCode = response.getStatusLine().getStatusCode();
					if (debugMode) System.out.println("LIMS status for order {} ==> {} "+ order + "," +statusCode);

					// 🚨 If not success, log and skip
					if (statusCode != 200 && statusCode != 201) {
						if (debugMode) System.out.println("Results for order " +order+ " not available. HTTP Status: {} " +statusCode);
						continue;   // Move to next orderId immediately
					}
					if (debugMode) System.out.println("Results for order " +order+ " is now available. HTTP Status: {} " +statusCode);
					// ✅ Only success reaches here
					HttpEntity entity = response.getEntity();
					if (entity != null) {
						String jsonString = EntityUtils.toString(entity, StandardCharsets.UTF_8);

						if (jsonString != null && !jsonString.isEmpty()) {
							LabwareFacilityWideResultsMapper.processResultsFromLims(jsonString);
							if (debugMode) System.out.println("Successfully processed LIMS result for order {} " +order);
						} else {
							if (debugMode) System.out.println("Empty response received for order {} " +order);
						}
					}
				}

			} catch (Exception e) {
				if (debugMode) System.out.println("Error fetching LIMS results for order {} " +order + "," + e);
				// automatically continues to next order
			}

			// Optional delay
			try {
				Thread.sleep(5000);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
		}
		// finally
        httpClient.close();
    }

	public static Integer getAgeInWeeks(Date birtDate, Date context) {
		DateTime d1 = new DateTime(birtDate.getTime());
		DateTime d2 = new DateTime(context.getTime());
		return Weeks.weeksBetween(d1, d2).getWeeks();
	}

	public static Integer getAgeInMonths(Date birtDate, Date context) {
		DateTime d1 = new DateTime(birtDate.getTime());
		DateTime d2 = new DateTime(context.getTime());
		return Months.monthsBetween(d1, d2).getMonths();
	}

}


