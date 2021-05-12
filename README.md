<h1>Overmind</h1>

Overmind is a spiking neural network (SNN) simulator for Android devices with a distributed architecture. 

The neural network is organized in sub-networks. Each sub-network is simulated on a separate terminal. Sub-networks exchange spikes over the internet using the User Datagram Protocol (UDP).

Overmind consists of 2 main components: 
  1) A client application, to be run on the Android terminals, that simulates the local sub-network and takes care of communications with other client applications
  2) A server application, that runs on a desktop pc and manages the topology of the virtual network that is made of the client terminals 
  
Communications among terminals are direct, i.e. the terminals form of a P2P network. The server facilitates the "UDP hole punching" technique to make direct communications among clients possible. A brief overview of the simulator can be found <a href="https://drive.google.com/file/d/1HXEZPquvL074W5A8zCYezKZsSuZJRPBV/view?usp=sharing">here</a>; more technical details can be found <a href="https://drive.google.com/file/d/1mpvIt8U_E-32HUVETb6icVovcQidOv6U/view?usp=sharing">here</a>.

<h2>OvermindClient</h2>

This is the client application that runs on Android devices. The flow of the application is the following:
  1) Establish connection with the server
  2) Connect with peer terminals
  3) Simulate local sub-network
  4) Send spikes to connected peer terminals
  
To achieve this, the application is sectioned in 2 parts: one part written in Java, that handles communications with the server and the peers, and one part written in C, which simulates the sub-network. The neural network simulation is done in OpenCL. 
