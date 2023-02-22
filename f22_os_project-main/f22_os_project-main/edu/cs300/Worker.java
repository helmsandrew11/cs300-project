package edu.cs300;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.*;
import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;  
import java.time.temporal.ChronoField; 

class Worker extends Thread{

	ArrayBlockingQueue<MeetingRequest> incomingRequests;
	ArrayBlockingQueue<MeetingResponse> outgoingResponse;
	String empId;
	String file;
	String empName;


	public Worker(String empId,ArrayBlockingQueue<MeetingRequest> 
		incomingRequests, ArrayBlockingQueue<MeetingResponse> outgoingResponse, 
		String file, String empName){

		this.incomingRequests=incomingRequests;
		this.outgoingResponse=outgoingResponse;
		this.empId=empId;
		this.file = file;
		this.empName = empName;
	}

	public void run() {
		DebugLog.log(" Thread ("+this.empId+") thread started ...");
		try {
			Calendar schedule = new Calendar(this.empId, this.file, 
				this.empName);
			
			//parse .dat
			schedule.makeCalendar();
			
			while (true) {
				MeetingRequest mtgReq = (MeetingRequest)this.incomingRequests.take();

				//make the update file and leave on reqId 0
				if (mtgReq.request_id == 0) {
					schedule.fileCalendar();

					break;
				}

				int reply = schedule.updateCalendar(mtgReq);

				DebugLog.log("Worker-" + this.empId + " " + mtgReq + 
					" pushing response "+mtgReq.request_id);
				
				//send outgoing with availability
				this.outgoingResponse.put(new 
					MeetingResponse(mtgReq.request_id,reply));
			}
		} catch(InterruptedException e){
			System.err.println(e.getMessage());
		}
	
	}

}


	class Meeting {
		String description;
		String location;
		int duration;
		LocalDateTime dateTime;

		public Meeting(String description, String location, 
			String datetime, int duration) {

			this.description = description;
			this.location = location;
			this.duration = duration;
			this.dateTime = LocalDateTime.parse(datetime);
		}

		public Meeting(MeetingRequest mtg) {
			this.description = mtg.description;
			this.location = mtg.location;
			this.duration = mtg.duration;
			this.dateTime = LocalDateTime.parse(mtg.datetime);
		}

		public String print() {
			//new meetings would have double quotes
			Pattern p = Pattern.compile(".*\"([^\"]*)\".*");
			Matcher m1 = p.matcher(description);
			if (m1.matches()) {
				description = m1.group(1);
			}
			
			m1 = p.matcher(location);
			if (m1.matches()) {
				location = m1.group(1);
			}
			
			return ("\"" + description + "\"," + "\"" + location + "\"," 
				+ dateTime + "," + duration);
		}



	}
	
	class Calendar {
		String schedule;
		String empId;
		String empName;
		TreeMap<LocalDateTime, Meeting> itenerary;  

		
		public Calendar(String empId, String file, String empName) {
			this.schedule = file;
			this.empId = empId;
			this.empName = empName;
			itenerary = new TreeMap<LocalDateTime, Meeting>();
		}

		public void makeCalendar() {
			int countMeetings = 0;
			int count = 0;
			String description = null;
			String location = null;
			String datetime = null;
			int duration = 0;
			String str = null;
			
			try {
//https://www.geeksforgeeks.org/java-program-to-extract-a-single-quote-enclosed-string-from-a-larger-string-using-regex/
				File f = new File(this.schedule);
				Scanner scanner = new Scanner(f);

				//Paranthesis indicate it is a group 
				// and signifies it can have substring enclosed in quotes
				Pattern p = Pattern.compile(".*\"([^\"]*)\".*");
				
				while (scanner.hasNextLine()) {
					String line = scanner.nextLine();

					//Scan the line for tokens
					try (Scanner rowScanner = new Scanner(line)) {
						rowScanner.useDelimiter(",");
						while (rowScanner.hasNext()) {
							//counter in order to assign correct values
							if (count == 0) {
								str = rowScanner.next();
								Matcher m1 = p.matcher(str);
						
								// matches() method looks for content in quotes
								if (m1.matches()) {
									description = m1.group(1);
								}

								count = 1;
							} else if (count == 1) {
								str = rowScanner.next();
								
								Matcher m1 = p.matcher(str);

								if (m1.matches()) {
									location = m1.group(1);
								}

								count = 2;
							} else if (count == 2) {
								datetime = rowScanner.next();

								count = 3;
							} else if (count == 3) {
								duration = Integer.parseInt(rowScanner.next());

								count = 0;
							} 
						}
						
						//put the new meeting on the calendar
						Meeting x = new Meeting(description, location, 
							datetime, duration);
						itenerary.put(x.dateTime, x);

						rowScanner.close();
					}
					
				}
				scanner.close();
			
			} catch (FileNotFoundException e) {
			e.printStackTrace();
			}

			
			
		}

		public void printCalendar() {
// https://www.javacodeexamples.com/print-hashtable-in-java-example/3154
			
			Set<Map.Entry<LocalDateTime, Meeting>> entries = 
				this.itenerary.entrySet();
 
			for(Map.Entry<LocalDateTime, Meeting> entry : entries ){
				System.out.println( "Day: " +entry.getKey()+ "Meeting: \n"+
					entry.getValue().print());
			}
		}

		public void fileCalendar() {
// https://www.javacodeexamples.com/print-hashtable-in-java-example/3154
// https://www.w3schools.com/java/java_files_create.asp
			
			try {
				String outName = this.schedule + ".bak";
				
				FileWriter myWriter = new FileWriter(outName);

				Set<Map.Entry<LocalDateTime, Meeting>> entries = 
					this.itenerary.entrySet();
 
				for(Map.Entry<LocalDateTime, Meeting> entry : entries ){
					myWriter.write(entry.getValue().print()+"\n");
				}

				myWriter.close();

			} catch (IOException e) {
				DebugLog.log("An error occurred.");
				e.printStackTrace();
			}
			
			
		}

// https://stackoverflow.com/questions/17106670/how-to-check-a-timeperiod-is-overlapping-another-time-period-in-java
		
		public int updateCalendar(MeetingRequest mtgReq) {
			Meeting pending = new Meeting(mtgReq);
			
// https://stackoverflow.com/questions/17106670/how-to-check-a-timeperiod-is-overlapping-another-time-period-in-java
			
			//if something's not at its exact time, put it in
			if(!itenerary.containsKey(pending.dateTime)) {
				itenerary.put(pending.dateTime, pending);
				
				//check if the lower one overlaps
				if(itenerary.lowerKey(pending.dateTime) != null) {
					if (itenerary.lowerKey(pending.dateTime).plusMinutes(itenerary.lowerEntry(pending.dateTime).getValue().duration).isAfter(pending.dateTime) ){
						return 0;
					}
				}
				//check if the upper one overlaps
				if 
				(itenerary.higherKey(pending.dateTime) != null) {
					if 
					(itenerary.higherKey(pending.dateTime).isBefore(pending.dateTime.plusMinutes(pending.duration))) {
						return 0;
					}
				}

				return 1;
			}

			return 0;
		}
	}