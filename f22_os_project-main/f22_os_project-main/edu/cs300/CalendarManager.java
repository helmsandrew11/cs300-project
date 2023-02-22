package edu.cs300;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDateTime;  
import java.time.temporal.ChronoField; 
import java.io.*;

public class CalendarManager {
	
	Hashtable<String,ArrayBlockingQueue<MeetingRequest>> empQueueMap;
	ArrayBlockingQueue<MeetingResponse> resultsOutputArray;
	
	public CalendarManager() {
		this.resultsOutputArray = new ArrayBlockingQueue<MeetingResponse>(30);
		empQueueMap = new Hashtable<String,ArrayBlockingQueue<MeetingRequest>>();
	}

	public void runner() {
		int count = 0;
		String empId = null;
		String file = null;
		String empName = null;

		try {
			File f = new File("employees.csv");
			Scanner scanner = new Scanner(f);
			//Read line
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine();

				//Scan the line
				try (Scanner rowScanner = new Scanner(line)) {
					//Scan until comma
					rowScanner.useDelimiter(",");
					while (rowScanner.hasNext()) {
						if (count == 0) {
							empId = rowScanner.next();
							count = 1;
						} else if (count == 1) {
							file = rowScanner.next();
							count = 2;
						} else if (count == 2) {
							empName = rowScanner.next();
							count = 0;

							ArrayBlockingQueue<MeetingRequest> q = 
								new ArrayBlockingQueue<MeetingRequest>(10);
							empQueueMap.put(empId, q);

							new Worker(empId,q, this.resultsOutputArray, 
								file, empName).start(); 

						}
					}
					rowScanner.close();	
				}	
			}
			scanner.close();
			
			new OutputQueueProcessor(this.resultsOutputArray).start();
			new InputQueueProcessor(this.empQueueMap).start();

		} catch (FileNotFoundException e) {
		e.printStackTrace();
		}
	}

	public static void main(String args[]) {
		
		CalendarManager mgr = new CalendarManager();
		
		mgr.runner();
	}
		

	class OutputQueueProcessor extends Thread {
		
		ArrayBlockingQueue<MeetingResponse> resultsOutputArray;
		
		OutputQueueProcessor(ArrayBlockingQueue<MeetingResponse> 
			resultsOutputArray){

			this.resultsOutputArray=resultsOutputArray;
		}
		
		public void run() {
			DebugLog.log(getName()+" processing responses ");
			while (true) {
				try {
					MeetingResponse res = resultsOutputArray.take();

					MessageJNI.writeMtgReqResponse(res.request_id, res.avail);
					DebugLog.log(getName()+" writing response "+res);
					
					//if 0 then end, but only after sending it back to the C side
					if(res.request_id==0){
						break;
					}
				} catch (Exception e) {
					DebugLog.log("Sys5OutputQueueProcessor error "
						+e.getMessage());
				}

			}
			
		}
		
	}

	class InputQueueProcessor extends Thread {
		Hashtable<String,ArrayBlockingQueue<MeetingRequest>> empQueueMap;
		
		InputQueueProcessor(Hashtable<String,ArrayBlockingQueue<MeetingRequest>> 
			empQueueMap){

			this.empQueueMap=empQueueMap;
		}

		public void run(){
			while (true) {
				MeetingRequest req = MessageJNI.readMeetingRequest();

				try {
					DebugLog.log(getName()+"recvd msg from queue for "
						+req.empId);

					if (req.request_id == 0) {

		// https://www.javacodeexamples.com/print-hashtable-in-java-example/3154
						Set<Map.Entry<String,ArrayBlockingQueue<MeetingRequest>>> 
							entries = this.empQueueMap.entrySet();
 
						//send a 0 to all the workers in order to end them
						for(Map.Entry<String,ArrayBlockingQueue<MeetingRequest>> 
							entry : entries ){

							entry.getValue().put(req);
							DebugLog.log(getName()+" pushing req "
								+req+" to "+req.empId);
						}

						break;
					}
					if (empQueueMap.containsKey(req.empId)) {
						empQueueMap.get(req.empId).put(req);
						DebugLog.log(getName()+" pushing req "
							+req+" to "+req.empId);
					}
					
				} catch (InterruptedException e) {
					DebugLog.log(getName()+" Error putting to emp queue"
						+req.empId);					
					e.printStackTrace();
				}
			}
			try {	
				//send reqId 0 back to end the program
				resultsOutputArray.put(new MeetingResponse(0,0));
			} catch (InterruptedException e) {				
				e.printStackTrace();
			}
		}
		
	}
}


