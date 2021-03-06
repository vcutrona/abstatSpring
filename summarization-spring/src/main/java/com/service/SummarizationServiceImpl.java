package com.service;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.google.code.externalsorting.ExternalSort;
import com.model.Dataset;
import com.model.SubmitConfig;
import com.summarization.export.AggregateConceptCounts;
import com.summarization.export.CalculateMinimalTypes;
import com.summarization.export.CalculatePropertyMinimalization;
import com.summarization.export.DatatypeSplittedPatternInference;
import com.summarization.export.Events;
import com.summarization.export.MainCardinality;
import com.summarization.export.ObjectSplittedPatternInference;
import com.summarization.export.ProcessDatatypeRelationAssertions;
import com.summarization.export.ProcessObjectRelationAssertions;
import com.summarization.export.ProcessOntology;

@Service
public class SummarizationServiceImpl implements SummarizationService {

	@Autowired
	DatasetService datasetService;
	@Autowired
	OntologyService ontologyService;
	@Autowired
	SubmitConfigService submitConfigService;
	
	@Autowired
	JavaMailSender emailSender;

	
	@Async("processExecutor")
	public void summarizeAsyncWrapper(SubmitConfig subCfg, String email) throws Exception  {
		summarize(subCfg, email);
	}
	

	public void summarize(SubmitConfig subCfg, String email)  throws Exception  {
		Events.summarization();
		String dsId = subCfg.getDsId();
		Dataset dataset = datasetService.findDatasetById(dsId);
		String datasetName = dataset.getName();
		String ontId = subCfg.getListOntId().get(0);
		String ontPath = new File(ontologyService.findOntologyById(ontId).getPath()).getCanonicalPath();
		String summary_dir = subCfg.getSummaryPath();
	
		String datasetSupportFileDirectory = summary_dir + "/reports/tmp-data-for-computation/";
        checkFile(summary_dir);
        checkFile(datasetSupportFileDirectory);
		
		//Process ontology
		ProcessOntology.main(new String[] {ontPath, datasetSupportFileDirectory});
        
		//Script bash/awk invocation
		split(datasetName);
		
 		String minTypeResult = summary_dir + "/min-types/min-type-results/";
 		String patternsPath = summary_dir + "/patterns/";
        String typesDirectory = "../data/DsAndOnt/dataset/" + datasetName + "/organized-splitted-deduplicated/";
        checkFile(minTypeResult);
        checkFile(patternsPath);

 		
        // core summarization
        coreSummarization(ontPath, typesDirectory, minTypeResult, patternsPath);
 		
        Path objectAkpGrezzo_target = Paths.get(patternsPath+"object-akp_grezzo.txt");
 		Path datatypeAkpGrezzo_target = Paths.get(patternsPath+"datatype-akp_grezzo.txt");

 		//Property minimalization
 		if(subCfg.isPropertyMinimaliz()) 
 			propertyMinimalization(objectAkpGrezzo_target, datatypeAkpGrezzo_target, patternsPath, ontPath);
 		//Inferenze
 		if(subCfg.isInferences()) 
 			inference(patternsPath, ontPath);
 		//Cardinalità
 		if(subCfg.isCardinalita()) 
 			cardinality(patternsPath);
    
		//save configuration
		submitConfigService.add(subCfg);
		
		//mail notification
		if(email!=null && !email.equals(""))
			sendMail(email, "Your summary is ready now!");
	}	
	
	
	private void coreSummarization(String ontPath, String typesDirectory, String minTypeResult, String patternsPath) throws Exception{
		//Minimal types
 		CalculateMinimalTypes.main(new String[] {ontPath, typesDirectory, minTypeResult});
 		//Aggregate concepts
 		AggregateConceptCounts.main(new String[] {minTypeResult, patternsPath});
 		//Process dt relational asserts
 		ProcessDatatypeRelationAssertions.main(new String[] {typesDirectory, minTypeResult, patternsPath});
 		//Process obj relational asserts
 		ProcessObjectRelationAssertions.main(new String[] {typesDirectory, minTypeResult, patternsPath});

 		//sposto i file akp_grezzo
 		Path objectAkpGrezzo_target = Paths.get(patternsPath+"object-akp_grezzo.txt");
 		Path datatypeAkpGrezzo_target = Paths.get(patternsPath+"datatype-akp_grezzo.txt");
 		Path datatypeAkpGrezzo_src = Paths.get("datatype-akp_grezzo.txt");
 		Path objectAkpGrezzo_src = Paths.get("object-akp_grezzo.txt");
 		Files.move(datatypeAkpGrezzo_src, datatypeAkpGrezzo_target, REPLACE_EXISTING);
 		Files.move(objectAkpGrezzo_src, objectAkpGrezzo_target,REPLACE_EXISTING);
 		
	}

	
	private void split(String datasetName) throws Exception {
 		String[] cmd = {"../pipeline/split-dataset.sh", datasetName};
 		Process p = Runtime.getRuntime().exec(cmd);
 		BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
 	    String line = "";
 	    while ((line = reader.readLine()) != null)
 	    	System.out.println(line);
	}
	
	private void propertyMinimalization(Path objectAkpGrezzo_target, Path datatypeAkpGrezzo_target, String patternsPath, String ontPath) throws Exception{
	    //ordino i file akp_grezzo
		ExternalSort.mergeSortedFiles(ExternalSort.sortInBatch(new File(objectAkpGrezzo_target.toString())), new File(patternsPath+"object-akp-grezzo_Sorted.txt"));
		ExternalSort.mergeSortedFiles(ExternalSort.sortInBatch(new File(datatypeAkpGrezzo_target.toString())), new File(patternsPath+"datatype-akp-grezzo_Sorted.txt"));
		//rinomino i file ordinati
		Path datatypeAkpGrezzo_sorted = Paths.get(patternsPath+"datatype-akp-grezzo_Sorted.txt");
		Path objectAkpGrezzo_sorted = Paths.get(patternsPath + "object-akp-grezzo_Sorted.txt");
		Files.move(datatypeAkpGrezzo_sorted, datatypeAkpGrezzo_sorted.resolveSibling("datatype-akp_grezzo.txt"), REPLACE_EXISTING);
		Files.move(objectAkpGrezzo_sorted, objectAkpGrezzo_sorted.resolveSibling("object-akp_grezzo.txt"), REPLACE_EXISTING);
		CalculatePropertyMinimalization.main(new String[] {ontPath, patternsPath});
		//sposto i file di output
		Path datatypeAkpGrezzo_Updated = Paths.get("datatype-akp_grezzo_Updated.txt");
		Path objectAkpGrezzo_Updated = Paths.get("object-akp_grezzo_Updated.txt");
		Files.move(datatypeAkpGrezzo_Updated, datatypeAkpGrezzo_target, REPLACE_EXISTING);
		Files.move(objectAkpGrezzo_Updated, objectAkpGrezzo_target, REPLACE_EXISTING);
		Path datatypeAkp = Paths.get(patternsPath + "datatype-akp.txt");
		Path objectAkp = Paths.get(patternsPath + "object-akp.txt");
		Path datatypeAkp_Updated = Paths.get("datatype-akp_Updated.txt");
		Path objectAkp_Updated = Paths.get("object-akp_Updated.txt");
		Files.move(datatypeAkp_Updated, datatypeAkp, REPLACE_EXISTING);
		Files.move(objectAkp_Updated, objectAkp, REPLACE_EXISTING);
		Path countDatatypeProperties_Updated = Paths.get("count-datatype-properties_Updated.txt");
		Path countObjectProperties_Updated = Paths.get("count-object-properties_Updated.txt");
		Path countDatatypeProperties = Paths.get(patternsPath + "count-datatype-properties.txt");
		Path countObjectProperties = Paths.get(patternsPath + "count-object-properties.txt");
		Files.move(countDatatypeProperties_Updated, countDatatypeProperties, REPLACE_EXISTING);
		Files.move(countObjectProperties_Updated, countObjectProperties, REPLACE_EXISTING);
	
	}
	
	
	private void inference(String patternsPath, String ontPath) throws Exception{
		checkFile(patternsPath + "AKPs_Grezzo-parts");
		checkFile(patternsPath+"specialParts_outputs");
		DatatypeSplittedPatternInference.main(new String[] {patternsPath, patternsPath + "AKPs_Grezzo-parts", ontPath, patternsPath+"specialParts_outputs"});
		ObjectSplittedPatternInference.main(new String[] {patternsPath, patternsPath + "AKPs_Grezzo-parts", ontPath, patternsPath+"specialParts_outputs"});
	}
	
	
	private void cardinality(String patternsPath) throws Exception{
		checkFile(patternsPath+"/Akps");
		checkFile(patternsPath+"/Properties");
		MainCardinality.main(new String[] {patternsPath});
	}
	
	
	private void checkFile(String path_dir) throws Exception{
		File dir = new File(path_dir);
		
		if(dir.exists())
			 FileUtils.deleteDirectory(dir);
		if (dir.mkdirs()) 
			System.out.println("Successfully created:" + dir);
	    else 
	        System.out.println("Failed to create: " + dir);
	}
	

    public void sendMail(String email, String text) {

        SimpleMailMessage message = new SimpleMailMessage(); 
        message.setTo(email); 
        message.setSubject("ABSTAT"); 
        message.setText(text);
        emailSender.send(message);
    }
}
