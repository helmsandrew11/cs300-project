#include <stdio.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/ipc.h>
#include <sys/msg.h>
#include "queue_ids.h"
#include "meeting_request_formats.h"
#include <assert.h>

pthread_mutex_t m[200];
pthread_cond_t c[200];
int done[200];


void *receiveMessage() {
    while (1) {
        int msqid;
        int msgflg = IPC_CREAT | 0666;
        key_t key;
        meeting_response_buf rbuf;

        key = ftok(FILE_IN_HOME_DIR,QUEUE_NUMBER);
        if (key == 0xffffffff) {
            fprintf(stderr,"Key cannot be 0xffffffff..fix queue_ids.h to link to existing file\n");
            return NULL;
        }

        if ((msqid = msgget(key, msgflg)) < 0) {
            int errnum = errno;
            fprintf(stderr, "Value of errno: %d\n", errno);
            perror("(msgget)");
            fprintf(stderr, "Error msgget: %s\n", strerror( errnum ));
        }

        
        // msgrcv to receive mtg request response type 1
        int ret;
        do {
            ret = msgrcv(msqid, &rbuf, sizeof(rbuf)-sizeof(long), 1, 0);//receive type 1 message

            int errnum = errno;
            if (ret < 0 && errno !=EINTR){
            fprintf(stderr, "Value of errno: %d\n", errno);
            perror("Error printed by perror");
            fprintf(stderr, "Error receiving msg: %s\n", strerror( errnum ));
            }
        } while ((ret < 0 ) && (errno == 4));

        if (rbuf.request_id == 0) { //once the java side has passed back the 0 message, stop the program
            exit(0);
        }
        
        //update the variable and signal up the sleeping thread
        pthread_mutex_lock(&m[rbuf.request_id]);
        if (rbuf.avail == 1) {
            done[rbuf.request_id] = 1;
        } else done[rbuf.request_id] = 2;    
        pthread_cond_signal(&c[rbuf.request_id]);
        pthread_mutex_unlock(&m[rbuf.request_id]);

        
        

        // fprintf(stderr,"msgrcv-mtgReqResponse: request id %d  avail %d: \n",rbuf.request_id,rbuf.avail);
    }

    return NULL;
}


typedef struct numbered_buf {
    meeting_request_buf mrb;
} numbered_buf;

void *sendMessage(void *arg) {    
    int msqid;
    int msgflg = IPC_CREAT | 0666;
    key_t key;

    key = ftok(FILE_IN_HOME_DIR,QUEUE_NUMBER);
    if (key == 0xffffffff) {
        fprintf(stderr,"Key cannot be 0xffffffff..fix queue_ids.h to link to existing file\n");
        return NULL;
    }

    if ((msqid = msgget(key, msgflg)) < 0) {
        int errnum = errno;
        fprintf(stderr, "Value of errno: %d\n", errno);
        perror("(msgget)");
        fprintf(stderr, "Error msgget: %s\n", strerror( errnum ));
    }
    
    numbered_buf *buf = (numbered_buf *) arg;
    
    buf->mrb.mtype = 2;

    pthread_mutex_lock(&m[buf->mrb.request_id]);
    if((msgsnd(msqid, &buf->mrb, SEND_BUFFER_LENGTH, IPC_NOWAIT)) < 0) {
        int errnum = errno;
        //fprintf(stderr,"%d, %ld, %d, %ld\n", msqid, buf->mrb.mtype, buf->mrb.request_id, SEND_BUFFER_LENGTH);
        perror("(msgsnd)");
        //fprintf(stderr, "Error sending msg: %s\n", strerror( errnum ));
        exit(1);
    } else //fprintf(stderr,"msgsnd--mtg_req: reqid %d empid %s descr %s loc %s date %s duration %d \n", buf->mrb.request_id,buf->mrb.empId,buf->mrb.description_string,buf->mrb.location_string,buf->mrb.datetime,buf->mrb.duration);
    
    while (done[buf->mrb.request_id] == 0) {
        pthread_cond_wait(&c[buf->mrb.request_id], &m[buf->mrb.request_id]);
    }
    
    pthread_mutex_unlock(&m[buf->mrb.request_id]);

    //print accept/reject
    if (done[buf->mrb.request_id] == 1) {
        printf("Meeting request %d for employee %s was accepted (%s @ %s starting %s for %d minutes)\n", 
                buf->mrb.request_id, buf->mrb.empId, buf->mrb.description_string, buf->mrb.location_string, buf->mrb.datetime, buf->mrb.duration);
    } else printf("Meeting request %d for employee %s was rejected due to conflict (%s @ %s starting %s for %d minutes)\n", 
                buf->mrb.request_id, buf->mrb.empId, buf->mrb.description_string, buf->mrb.location_string, buf->mrb.datetime, buf->mrb.duration);

    
    free(arg);
    return NULL;
}


int main(int argc, char *argv[]){
    pthread_t pid[200];

    int countThreads = 0;
    
    char *buffer = NULL;
    size_t len = 0;
    
    int i;
    
    char **args = malloc(6 * sizeof(char *)); // Allocate row pointers
    for(i = 0; i < 6; i++) {
        args[i] = malloc(30 * sizeof(char));  // Allocate each row separately
    }

    int count = 0;
    
    //receiving thread
    pthread_t t;
    if (pthread_create(&t, NULL, &receiveMessage, NULL) != 0) {
        fprintf(stderr, "Failed to created thread");
    }
    
    while (getline(&buffer, &len, stdin)) {
        //parsing input around the commas
        char *token = strtok(buffer, ",");
        while (token) {
            strcpy(args[count], token);
            
            count++;
            token = strtok(NULL, ",");   
        }

        //putting input into a struct
        numbered_buf *buf = malloc(sizeof(*buf));
        buf->mrb.mtype = 2;
        buf->mrb.request_id=atoi(args[0]);
        strncpy(buf->mrb.empId,args[1],EMP_ID_MAX_LENGTH);
        strncpy(buf->mrb.description_string,args[2],DESCRIPTION_MAX_LENGTH);
        strncpy(buf->mrb.location_string,args[3],LOCATION_MAX_LENGTH);
        strncpy(buf->mrb.datetime,args[4],DATETIME_LENGTH);
        buf->mrb.duration=atoi(args[5]);

        int rc = pthread_mutex_init(&m[buf->mrb.request_id], NULL);
        assert(rc == 0);

        rc = pthread_cond_init(&c[buf->mrb.request_id], NULL);
        assert(rc == 0);
        done[buf->mrb.request_id] = 0;

        //last thread
        if (buf->mrb.request_id == 0) {
            //join all the previous threads
            for (i = 0; i < countThreads; i++) {
                if (pthread_join(pid[i], NULL) != 0) {
                    fprintf(stderr, "Failed to join thread\n");
                }
            }
            
            //free memory before the kill
            for (i = 0; i < 6; i++ )
            {
                free(args[i]);
            }
            free(args);
            free(buffer);
            
            //make the id:0 thread
            if (pthread_create(&pid[countThreads], NULL, &sendMessage, buf) != 0) {
                fprintf(stderr, "Failed to created thread");
            }

            //join last thread
            if (pthread_join(pid[countThreads], NULL) != 0) {
                fprintf(stderr, "Failed to join thread\n");
            }

            return 0;
        } else {
            if (pthread_create(&pid[countThreads], NULL, &sendMessage, buf) != 0) {
                fprintf(stderr, "Failed to created thread");
            }
        }

        //reset input count for next line
        count = 0;
        countThreads++;
    }

    return 0;
}

