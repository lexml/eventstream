package br.gov.lexml.eventfilteroutputstream

import java.io.FilterOutputStream
import java.io.OutputStream
import org.apache.commons.io.output.NullOutputStream
import java.io.InputStream
import scala.collection.mutable.Buffer
import java.io.IOException

abstract sealed class Event extends Product {
  val length : Int = 0
}

object Event {
  def streamTo(e : Iterable[Event], os : OutputStream) : Unit = {
    val i = e.iterator
    var closed = false
    while(i.hasNext && !closed) {
      i.next() match {
        case Close => closed = true ; os.close()
        case Flush => os.flush()
        case Chunk(data,off,len) => os.write(data,off,len)
      }
    }
  } 
  def readFrom(is : InputStream) : Stream[Chunk] = {
    val buffer = Array.ofDim[Byte](65536)
    def s() : Stream[Chunk] = {
      val n = is.read(buffer)
      if(n < 0) { Stream.Empty } else {
        Chunk.copy(buffer,0,n) #:: s()
      }
    }
    s()
  }
}

final case class Chunk(
    data : Array[Byte], 
    off : Int, 
    override val length : Int) extends Event

object Chunk {
  def copy(data : Array[Byte], off : Int, len : Int) : Chunk = {    
    val a = Array.ofDim[Byte](data.length)
    Array.copy(data,off,a,0,len)
    Chunk(a,0,a.length)    
  }
  def apply(data : Array[Byte],_copy : Boolean = true) : Chunk = 
   if(_copy) { copy(data,0,data.length) } 
   else { apply(data,0,data.length) }
}

case object Flush extends Event

case object Close extends Event

class EventFilterOutputStream(
        handler : Event => Unit, 
        _out : OutputStream = new NullOutputStream) extends FilterOutputStream(_out) {
  override def write(b : Int) = {
    super.write(b)
    handler(Chunk(Array(b.toByte),false))
  }  
  override def write(data : Array[Byte], off : Int, len : Int) = {    
    handler(Chunk.copy(data,off,len))        
  }
  override def flush() {
    super.flush()
    handler(Flush)
  }
  override def close() {
    super.close()
    handler(Close)
  }
}

object EventFilterOutputStream {
  def passtroughHandler(os : OutputStream) : Event => Unit = {
    case Flush =>  os.flush()
    case Chunk(data,off,len) => os.write(data,off,len)
    case Close => os.close()
  } 
}

class EventInputStream(_src : Iterable[Chunk]) extends InputStream {
  private var src : Stream[Chunk] = _src.toStream.filter(_.length > 0)
  private var markBuffer : Option[Buffer[Chunk]] = None  
  private var markLimit = 0
  private var markLength = 0
  private var closed : Boolean = false
  
  private def ensureNotClosed[T](f : => T) = 
      if(closed) { throw new IOException("Stream closed") } else { f }
  
  private def discard(c : Chunk) = markBuffer match {
    case None => ()
    case _ if markLength >= markLimit => ()
    case Some(b) if (markLength + b.length <= markLimit) => b += c ; markLength += b.length
    case Some(b) => b += Chunk(c.data,c.off,markLimit - markLength) ; markLength = markLimit
  } 
      
  override def read() : Int = {
    var a = Array.ofDim[Byte](1)
    val r = read(a,0,1)
    if(r < 0) { -1 } else { a(0) }
  } 
  
  override def read(b : Array[Byte], off : Int, len : Int) = ensureNotClosed {    
    var num = len
    while(num > 0 && !src.isEmpty) {     
      val block = src.head match {        
        case c@Chunk(data,off,len) if len <= num => src = src.tail ; c
        case c@Chunk(data,off,len) => src = Chunk(data,off+num.toInt,len-(num.toInt)) +: src.tail ; Chunk(data,off,num)
      }
      Array.copy(b,off,block,block.off,block.length)
      num -= block.length
      discard(block)
    }
    if (num == len) { -1 } else { len - num}
  }
  
  override def skip(n : Long) : Long = ensureNotClosed {
    var num = n
    while(num > 0 && !src.isEmpty) {
      src.head match {        
        case c@Chunk(data,off,len) if len <= num => discard(c) ; src = src.tail ; num -= len
        case c@Chunk(data,off,len) => src = Chunk(data,off+num.toInt,len-(num.toInt)) +: src.tail
      }
    }
    num
  }
  
  override def mark(_markLimit : Int) : Unit = {
    if(!closed || _markLimit > 0) {
       markBuffer = Some(Buffer())
       markLimit = _markLimit       
    } else {
      markBuffer = None
      markLimit = 0
    }   
    markLength = 0
  }
  
  override def reset() : Unit = ensureNotClosed {
    markBuffer foreach { b =>  src = b.toStream #::: src }  
    markBuffer = None
    markLimit = 0
    markLength = 0
  }
  
  override def available() : Int = if(closed) { 0 } else { src.headOption.map(_.length).getOrElse(0) }
      
  override def close() : Unit = { 
    closed = true
    src = Stream()
    markBuffer = None
  }
      
}