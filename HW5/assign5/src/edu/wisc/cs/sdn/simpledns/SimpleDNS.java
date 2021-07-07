package edu.wisc.cs.sdn.simpledns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Scanner;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataAddress;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataName;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataString;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;

public class SimpleDNS
{

    private static final int Port = 8053;
    private static final int Send = 53;

    public static void main(String[] args) throws Exception {
        System.out.println("Hello, DNS!");
        if (args.length != 4){
            return;
        }
        InetAddress rdns_ip;
        File ec2;

        if (!args[0].equals("-r") || !args[2].equals("-e")){
            return;
        }
        rdns_ip = InetAddress.getByName(args[1]);
        ec2 = new File(args[3]);
        if (rdns_ip == null || ec2 == null){
            System.out.println("Sth wrong");
            return;
        }
        ArrayList<EC2> ec2Records = getEC2(ec2);

        System.out.println("Start");

        try {
            DatagramSocket dnsSocket = new DatagramSocket(Port);
            DatagramPacket dnsPacket = new DatagramPacket(new byte[2048], 2048);
            while (true){
                dnsSocket.receive(dnsPacket);
                // dns is the packet received for processing
                DNS dns = DNS.deserialize(dnsPacket.getData(), dnsPacket.getLength());

                // todo: add a loop to resolve all questions in the dns request 0...n
                if(dns.getOpcode() != 0 || dns.getQuestions().isEmpty() || !dnsType(dns.getQuestions().get(0).getType()))
                    continue;

                DatagramPacket resolved = new DatagramPacket(new byte[2048], 2048);
                if (dns.isRecursionDesired()){
                    sendDNS(dnsSocket, recursiveDNS(dns, rdns_ip, ec2Records, dnsSocket), dnsPacket.getAddress(),dnsPacket.getPort());
                } else {
                    DatagramSocket Socket = new DatagramSocket();
                    sendDNS(Socket, dns, rdns_ip, Send);
                    Socket.receive(resolved);
                    // this is the reply
                    Socket.close();
                    sendDNS(dnsSocket, DNS.deserialize(resolved.getData(),resolved.getLength()), dnsPacket.getAddress(),dnsPacket.getPort());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static boolean dnsType(short s){
        return s == DNS.TYPE_A || s == DNS.TYPE_AAAA || s == DNS.TYPE_CNAME || s == DNS.TYPE_NS;
    }

    private static void sendDNS(DatagramSocket socket, DNS dns, InetAddress dst, int port) throws IOException {
        byte[] temp = dns.serialize();
        socket.send(new DatagramPacket(temp, temp.length, dst, port));
    }

    private static DNS recursiveDNS(DNS dns, InetAddress rdns_ip, ArrayList<EC2> ec2List, DatagramSocket socket) throws Exception {
        //DatagramSocket socket = new DatagramSocket();
        DatagramPacket receiver = new DatagramPacket(new byte[2048], 2048);

        List<DNSResourceRecord> cnames = new ArrayList<DNSResourceRecord>();
        // in the case that we have resolved nothing from this iteration of request,
        // we need to maintain a higher level of NS authorities that the dns request has done so far
        // e.g. a wrong domain...
        // them send back a higher level dns request
        List<DNSResourceRecord> authorities = new ArrayList<DNSResourceRecord>();
        List<DNSResourceRecord> additionals = new ArrayList<DNSResourceRecord>();

        DNS tempDNS = dns;
        sendDNS(socket, dns, rdns_ip, Send);

        while (true){
            System.out.println("Line 95 receiver");
            socket.receive(receiver);
            System.out.println("Line 97 receiver");

            DNS receivedDNS = DNS.deserialize(receiver.getData(), receiver.getLength());
            List<DNSResourceRecord> tempAnswers = receivedDNS.getAnswers();
            List<DNSResourceRecord> tempAuthorities = receivedDNS.getAuthorities();
            List<DNSResourceRecord> tempAdditional = receivedDNS.getAdditional();


            if (tempAuthorities.size() != 0){
                for (DNSResourceRecord as: tempAuthorities) {
                    if (dnsType(as.getType())){
                        authorities = tempAuthorities;
                        break;
                    }
                }
            }

            if (tempAdditional.size() != 0){
                additionals = tempAdditional;
            }

            // start
            if (tempAnswers.size() == 0){
                System.out.println("Line 125 IF");
                // todo: if question type is NS, can we just return?
//                if (dns.getQuestions().get(0).getType() == DNS.TYPE_NS){
//                    // DNS client question -> return a packet and send
//                    DNS temp = new DNS();
//                    temp.setQuery(false);
//                    temp.setOpcode(DNS.OPCODE_STANDARD_QUERY);
//                    temp.setAuthoritative(false);
//                    temp.setTruncated(false);
//                    temp.setRecursionAvailable(true);
//                    temp.setRecursionDesired(true);
//                    temp.setAuthenicated(false);
//                    temp.setCheckingDisabled(false);
//                    temp.setRcode(DNS.RCODE_NO_ERROR);
//                    temp.setQuestions(receivedDNS.getQuestions());
//                    temp.setAnswers(receivedDNS.getAdditional());
//                    temp.setId(dns.getId());
//
//                    //socket.close();
//                    return temp;
//                }

                boolean found = false;
                for (DNSResourceRecord as: tempAuthorities) {
                    String n_name = ((DNSRdataName) as.getData()).getName();
                    if (as.getType() == DNS.TYPE_NS){
                        for (DNSResourceRecord ad: tempAdditional){
                            if (ad.getType() == DNS.TYPE_A && ad.getName().equals(n_name)){
                                InetAddress addr = ((DNSRdataAddress)ad.getData()).getAddress();
                                System.out.println("Line 155 IF" + ad.getName() + n_name);
                                sendDNS(socket, tempDNS, addr, Send);
                                found = true;
                                break;
                            }
                        }
                        if (found){
                            break;
                        }
                    }
                }
                // todo: if not found return a packet with sth??? Answer: to find the ip of NS from ROOT
                if (found == false){ System.out.println("NOT FOUND");
                    DNS respond = new DNS();
                    reply(respond);
                    for (DNSResourceRecord cname : cnames){
                        tempAnswers.add(cname);
                    }

                    respond.setQuestions(dns.getQuestions());
                    respond.setAnswers(tempAnswers);
                    respond.setId(dns.getId());

                    //socket.close();
                    //sendDNS(socket, respond, rdns_ip, Send);
                    return respond;
                }

            } else {
                // there is an answer
                DNSResourceRecord answ = tempAnswers.get(0);

                // !!! only if we want to find CNAME or a web only contains a cname can the answer be the first record
                if (answ.getType() == DNS.TYPE_CNAME){  // require further sending
                    System.out.println("answ type: CNAME" + ((DNSRdataName) answ.getData()).getName() + receivedDNS.getQuestions().get(0).getType());
                    cnames.add(answ); // for next query usage, and store at least one of the cnames of the query
                    DNS newRequest = new DNS();
                    newRequest.setQuery(true);
                    newRequest.setOpcode((byte) 0);
                    newRequest.setTruncated(false);
                    newRequest.setRecursionDesired(true);
                    newRequest.setAuthenicated(false);
                    newRequest.setId(dns.getId());


                    DNSQuestion tempQ = new DNSQuestion();
                    tempQ.setName(((DNSRdataName) answ.getData()).getName());
                    tempQ.setType(dns.getQuestions().get(0).getType());
                    newRequest.addQuestion(tempQ);

                    //sendDNS(socket,newRequest,rdns_ip,Send);
                    socket.send(new DatagramPacket(newRequest.serialize(),newRequest.getLength(),rdns_ip, Send));
                    tempDNS = newRequest;  // find the ip of the new name
                    continue;
                }


                List<DNSResourceRecord> fin_ans_rep = new ArrayList<>(tempAnswers);
                // after this, DNS request is finished check EC2 options
                if (dns.getQuestions().get(0).getType() == DNS.TYPE_A){
                    for (DNSResourceRecord ans:tempAnswers){
                        if(ans.getType() == DNS.TYPE_A){
                            // check the EC2 todo: how to check???
                            String IPDATA = ((DNSRdataAddress) ans.getData()).getAddress().toString().
                                    replace("/", "");
                            for (EC2 ec2Entry: ec2List) {
                                if (ec2Entry.match(IPDATA)){
                                    DNSResourceRecord txt = new DNSResourceRecord();
                                    txt.setName(ans.getName());
                                    txt.setType(DNS.TYPE_TXT);
                                    DNSRdataString text = new DNSRdataString(ec2Entry.text+IPDATA); // todo: IPDATA correct???
                                    txt.setData(text);
                                    fin_ans_rep.add(txt);
                                    break;
                                }
                            }
                        }
                    }
                }

                for(DNSResourceRecord cnm: cnames){
                    fin_ans_rep.add(cnm);
                }

                DNS finalAnswer = new DNS();
                reply(finalAnswer);

                if (tempAuthorities.size() == 0){
                    finalAnswer.setAuthorities(authorities);
                } else {
                    finalAnswer.setAuthorities(tempAuthorities);
                }
                if (tempAdditional.size() == 0){
                    finalAnswer.setAdditional(additionals);
                } else {
                    finalAnswer.setAdditional(tempAdditional);
                }

                finalAnswer.setQuestions(dns.getQuestions());
                finalAnswer.setAnswers(fin_ans_rep);
                finalAnswer.setId(dns.getId());

                //socket.close();
                return finalAnswer;
            }
        }
    }
    private static ArrayList<EC2> getEC2(File ec2) throws FileNotFoundException {
        ArrayList<EC2> re = new ArrayList<>();
        Scanner scan = new Scanner(ec2);
        while (scan.hasNext()) {
            String entry = scan.nextLine();
            String[] separate = entry.split(",");

            re.add(new EC2(separate[1],separate[0]));
        }
        return re;
    }

    private static void reply(DNS finalAnswer){
        finalAnswer.setOpcode(DNS.OPCODE_STANDARD_QUERY);
        finalAnswer.setQuery(false);
        finalAnswer.setAuthoritative(false);
        finalAnswer.setTruncated(false);
        finalAnswer.setRecursionAvailable(true);
        finalAnswer.setRecursionDesired(true);
        finalAnswer.setAuthenicated(false);
        finalAnswer.setCheckingDisabled(false);
        finalAnswer.setRcode(DNS.RCODE_NO_ERROR);
    }
}

class EC2{
    int ip;
    int mask;
    String text;  // in the format of XXX-xx.xx.xx.xx


    public EC2(String loc, String net){
        text = loc + "-";
        String temp[] = net.split("/");
        System.out.println(temp[0] + " | " + temp[1]);
        int num = Integer.parseInt(temp[1]);
        int x = -1  ;
        mask = x << 32 - num;

        int y = 0;
        String temp1[] = temp[0].split("\\.");
        if (temp1.length != 4){
            System.out.println("WRONG lenth"+temp1[0] + temp1[1] + temp1.length);
        }
        for (int i = 0; i < 3; i ++){
            y += Integer.parseInt(temp1[i]);
            y = y << 8;
        }
        y += Integer.parseInt(temp1[3]);
        ip = y;
    }

    public boolean match(String IP){
        int y = 0;
        String temp1[] = IP.split("\\.");
        if (temp1.length != 4){
            System.out.println("WRONG lenth");
        }
        for (int i = 0; i < 3; i ++){
            y += Integer.parseInt(temp1[i]);
            y = y << 8;
        }
        y += Integer.parseInt(temp1[3]);
        return (ip & mask) == (y & mask);
    }
}
